package io.jeph.pipkin.data

import android.util.Log
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.RemoteAuth
import io.jeph.pipkin.host.RemoteConnectUi
import io.jeph.pipkin.host.RemoteFailure
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.host.RemoteHostsState
import io.jeph.pipkin.host.RemotePrompt
import io.jeph.pipkin.host.RemoteReply
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * The remote hosts the user added, their stand-in folders and which are connected, as the host last reported them.
 * [state] starts from the last result Pipkin saw, so remote workspaces are labelled before Termux answers. Changes
 * re-read it; they throw [HostRejected] or [io.jeph.pipkin.host.HostFailure].
 */
internal class RemoteHosts(
  private val hostClient: HostClient,
  private val preferences: Preferences,
  private val passwords: RemotePasswords,
  private val keepAlive: KeepAlive,
  private val scope: CoroutineScope,
) {
  private val stateFlow = MutableStateFlow(cached() ?: RemoteHostsState())
  private val connectedState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
  private val hostKeyChangedState = MutableStateFlow<Set<String>>(emptySet())
  private val refreshErrorState = MutableStateFlow<String?>(null)
  private val loadedState = MutableStateFlow(false)
  private val refreshLock = Mutex()
  private val installState = MutableStateFlow<OpenSshInstall>(OpenSshInstall.Idle)
  private val reconnectingState = MutableStateFlow<Set<String>>(emptySet())

  /** The [reconnect] running for each host, by id, which a second call for it joins. */
  private val reconnects = mutableMapOf<String, Deferred<Reconnect>>()

  /** How long [reconnect] waits before trying each host again after it failed, and why it failed. */
  private val backoffs = mutableMapOf<String, Backoff>()

  val state: StateFlow<RemoteHostsState> = stateFlow.asStateFlow()

  /** Whether each host has a live connection, by id; a host missing from it hasn't been checked yet. */
  val connected: StateFlow<Map<String, Boolean>> = connectedState.asStateFlow()

  /** Hosts whose key no longer matches the one accepted before, seen since Pipkin started; Forget host key fixes them. */
  val hostKeyChanged: StateFlow<Set<String>> = hostKeyChangedState.asStateFlow()

  /** Why the last refresh failed; null after a successful one. */
  val refreshError: StateFlow<String?> = refreshErrorState.asStateFlow()

  /** Whether [state] comes from this run's host rather than the saved copy. */
  val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

  /** The OpenSSH install [installOpenSsh] started, while it runs and after it fails. */
  val install: StateFlow<OpenSshInstall> = installState.asStateFlow()

  /** Hosts with a saved password, by id, each with whether the server rejected it, after which Pipkin stops sending it. */
  val savedPasswords: StateFlow<Map<String, Boolean>> = passwords.saved

  /** Hosts [reconnect] is signing in again now. */
  val reconnecting: StateFlow<Set<String>> = reconnectingState.asStateFlow()

  /** Re-reads the hosts and their connections in the background. */
  fun refresh() {
    scope.launch { load() }
  }

  /** Re-reads `remote_hosts`, then each host's connection. Never throws; a failure lands in [refreshError]. */
  suspend fun load() {
    refreshLock.withLock {
      refreshErrorState.value = try {
        val loaded = hostClient.remoteHosts()
        publish(loaded)
        // Removed, switched to another sign-in, or moved to another address: the password was for the host as it was.
        withContext(Dispatchers.IO) { passwords.prune(loaded.hosts) }
        val ids = loaded.hosts.map(RemoteHost::id)
        connectedState.value = if (ids.isEmpty()) emptyMap() else hostClient.remoteStatus(ids)
        null
      } catch (failure: IOException) {
        failure.message.orEmpty()
      } catch (rejected: HostRejected) {
        rejected.error
      } catch (error: JSONException) {
        error.message.orEmpty()
      }
    }
  }

  /** Saves [host], adding it when it has no id; returns it as saved, with its id. */
  suspend fun save(host: RemoteHost): RemoteHost {
    val saved = hostClient.remoteHostSave(host)
    load()
    return saved
  }

  /** Forgets host [id], after its workspaces left the sidebar. */
  suspend fun remove(id: String) {
    hostClient.remoteHostRemove(id)
    hostKeyChangedState.update { it - id }
    passwords.forget(id)
    load()
  }

  /** Whether saving [edited] over [current] deletes the password saved for it, as for a changed address or port. */
  fun forgetsPassword(current: RemoteHost, edited: RemoteHost): Boolean = passwords.forgetsOnSave(current, edited)

  fun forgetPassword(id: String) {
    passwords.forget(id)
  }

  /**
   * Connects host [id], asking [ui] what `ssh` needs. A password host's saved password answers its own prompt without
   * asking; when the server refuses it, Pipkin stops using it and the connect starts again, asking [ui] for the
   * password. A password typed with Remember ticked is saved once it signs in. Cancelling the caller, or a null
   * answer, ends it; a null answer throws [HostRejected] with [RemoteFailure.CONNECT_CANCELLED].
   */
  suspend fun connect(id: String, ui: RemoteConnectUi) {
    val host = stateFlow.value.host(id)
    val signIn = SignIn(host, savedPassword(host), ui)
    try {
      signIn(id, signIn, unattended = false)
    } catch (rejected: HostRejected) {
      if (rejected.code != RemoteFailure.AUTH_FAILED || !signIn.sentSaved) throw rejected
      passwords.reject(id)
      signIn(id, SignIn(host, saved = null, ui), unattended = false)
    }
    synchronized(reconnects) { backoffs.remove(id) }
  }

  /**
   * Signs password host [id] in again with its saved password, asking no one, as when its connection dropped. One runs
   * per host at a time, and a second call joins it. It refuses a host key the user hasn't accepted, so the password goes
   * only to the server they checked, and answers no other question. After a failure it waits before trying again, from
   * [FIRST_BACKOFF_MILLIS] up to [MAX_BACKOFF_MILLIS]; after the server refused the password, it stops until a new one
   * is saved.
   */
  suspend fun reconnect(id: String): Reconnect {
    val run = synchronized(reconnects) {
      reconnects.getOrPut(id) { scope.async(start = CoroutineStart.LAZY) { reconnectOnce(id) } }
    }
    return run.await()
  }

  private suspend fun reconnectOnce(id: String): Reconnect {
    try {
      val host = stateFlow.value.host(id)
      val saved = savedPassword(host) ?: return Reconnect.Skipped
      val now = TimeSource.Monotonic.markNow()
      synchronized(reconnects) { backoffs[id] }?.takeIf { now < it.until }?.let { return Reconnect.Failed(it.failure) }
      reconnectingState.update { it + id }
      return try {
        signIn(id, SignIn(host, saved, ui = null), unattended = true)
        synchronized(reconnects) { backoffs.remove(id) }
        Reconnect.Connected
      } catch (rejected: HostRejected) {
        val failure = RemoteFailure.of(rejected)
        if (rejected.code == RemoteFailure.AUTH_FAILED) {
          passwords.reject(id)
        } else {
          synchronized(reconnects) {
            val previous = backoffs[id]?.waitMillis
            val wait = previous?.let { (it * 2).coerceAtMost(MAX_BACKOFF_MILLIS) } ?: FIRST_BACKOFF_MILLIS
            backoffs[id] = Backoff(now + wait.milliseconds, wait, failure)
          }
        }
        Reconnect.Failed(failure)
      } catch (_: IOException) {
        // Termux didn't answer; the next call or check tries again.
        Reconnect.Skipped
      } finally {
        reconnectingState.update { it - id }
      }
    } finally {
      synchronized(reconnects) { reconnects.remove(id) }
    }
  }

  /** [host]'s saved password, decrypted off the main thread; null for a host that signs in another way. */
  private suspend fun savedPassword(host: RemoteHost?): RemotePasswords.Saved? {
    if (host?.auth != RemoteAuth.Password) return null
    return withContext(Dispatchers.IO) { passwords.password(host) }
  }

  /** One `remote_connect` of host [id], answered by [signIn]; saves the password it was told to remember. */
  private suspend fun signIn(id: String, signIn: SignIn, unattended: Boolean) {
    try {
      keepAlive.acquire("remote connect").use { hostClient.connectRemote(id, signIn, unattended) }
    } catch (rejected: HostRejected) {
      noteFailure(id, rejected.code)
      throw rejected
    }
    connectedState.update { it + (id to true) }
    hostKeyChangedState.update { it - id }
    val host = signIn.host
    val typed = signIn.typed
    if (host != null && typed != null) {
      withContext(Dispatchers.IO) {
        if (typed.remember) passwords.save(host, typed.prompt, typed.password) else passwords.forget(id)
      }
    }
    // The first connect learns the host's home folder.
    load()
  }

  /**
   * Answers a connect's prompts: [saved]'s password for the one prompt it was saved for, once, and everything else
   * through [ui], offering to remember a password host's password; with no [ui], nothing else is answered.
   */
  private inner class SignIn(
    val host: RemoteHost?,
    private val saved: RemotePasswords.Saved?,
    private val ui: RemoteConnectUi?,
  ) : RemoteConnectUi {
    /** Whether [saved] was sent, so a refused sign-in means the server refused it. */
    var sentSaved = false
      private set

    /** The password the user typed last, to save or forget once it signs in. */
    var typed: Typed? = null
      private set

    override fun onPrompt(prompt: RemotePrompt, reply: RemoteReply) {
      if (saved != null && !sentSaved && prompt.kind == RemotePrompt.Kind.Password && prompt.text == saved.prompt) {
        sentSaved = true
        scope.launch { reply.answer(saved.password) }
        return
      }
      if (ui == null) {
        scope.launch { reply.answer(null) }
        return
      }
      val savable = host != null && host.auth == RemoteAuth.Password && prompt.kind == RemotePrompt.Kind.Password
      val shown = prompt.copy(savable = savable, savedRejected = savable && passwords.saved.value[host.id] == true)
      ui.onPrompt(shown, if (savable) RememberingReply(prompt.text, reply) else reply)
    }

    private inner class RememberingReply(private val prompt: String, private val reply: RemoteReply) : RemoteReply {
      override suspend fun answer(answer: String?, remember: Boolean): Boolean {
        if (answer != null) typed = Typed(prompt, answer, remember)
        return reply.answer(answer)
      }
    }
  }

  private class Typed(val prompt: String, val password: String, val remember: Boolean)

  private class Backoff(val until: TimeSource.Monotonic.ValueTimeMark, val waitMillis: Long, val failure: RemoteFailure)

  suspend fun disconnect(id: String) {
    hostClient.remoteDisconnect(id)
    connectedState.update { it + (id to false) }
  }

  suspend fun forgetHostKey(id: String) {
    hostClient.remoteForgetHostKey(id)
    hostKeyChangedState.update { it - id }
    synchronized(reconnects) { backoffs.remove(id) }
  }

  /** Pipkin's public key line, made first if needed. */
  suspend fun publicKey(): String {
    state.value.publicKey?.let { return it }
    val key = hostClient.remoteKey()
    publish(state.value.copy(publicKey = key))
    return key
  }

  /**
   * Replaces Pipkin's key with a new one and returns its public line. The hosts that sign in with the key are
   * disconnected, and refuse Pipkin until the user adds the new line to their `authorized_keys`.
   */
  suspend fun newKey(): String {
    val key = hostClient.remoteNewKey()
    publish(state.value.copy(publicKey = key))
    load()
    return key
  }

  /** The stand-in folder for [path] on host [hostId], which the app uses as the workspace's folder. */
  suspend fun workspace(hostId: String, path: String): String {
    val cwd = hostClient.remoteWorkspace(hostId, path)
    // Known at once, so the new workspace's row names its host before the next refresh.
    load()
    return cwd
  }

  /**
   * Installs OpenSSH in Termux in the background unless an install is running, then re-reads [state], which then says
   * it is installed. It runs in the app's scope so leaving the screen doesn't interrupt `pkg` halfway.
   */
  fun installOpenSsh() {
    if (installState.value is OpenSshInstall.Running) return
    installState.value = OpenSshInstall.Running(line = "")
    scope.launch {
      installState.value = try {
        keepAlive.acquire("OpenSSH install").use {
          hostClient.installOpenSsh { line -> installState.value = OpenSshInstall.Running(line) }
        }
        load()
        OpenSshInstall.Idle
      } catch (rejected: HostRejected) {
        OpenSshInstall.Failed(rejected.error)
      } catch (failure: IOException) {
        OpenSshInstall.Failed(failure.message.orEmpty())
      }
    }
  }

  /** Whether [cwd] is a remote workspace's stand-in. */
  fun isRemote(cwd: String): Boolean = stateFlow.value.workspaces.any { folderKey(it.cwd) == folderKey(cwd) }

  /** Records what a remote operation's failure on host [hostId] says about its connection and its key. */
  fun noteFailure(hostId: String, code: String) {
    if (code == RemoteFailure.HOST_KEY_CHANGED) hostKeyChangedState.update { it + hostId }
    if (RemoteFailure.isDisconnect(code)) connectedState.update { it + (hostId to false) }
  }

  /**
   * Records that an operation on host [hostId] just reached it. A host reached before its home folder was known has had
   * it saved by then, so the hosts are read again to label its folders `~/…`.
   */
  fun noteReached(hostId: String) {
    connectedState.update { it + (hostId to true) }
    if (stateFlow.value.host(hostId)?.home == null) refresh()
  }

  private fun publish(loaded: RemoteHostsState) {
    stateFlow.value = loaded
    loadedState.value = true
    preferences.setRemoteHostsCache(loaded.toJson().toString())
  }

  private fun cached(): RemoteHostsState? = try {
    preferences.remoteHostsCache()?.let { RemoteHostsState.from(JSONObject(it)) }
  } catch (error: JSONException) {
    Log.w(TAG, "Ignoring an unreadable saved remote hosts list", error)
    null
  }

  private companion object {
    const val TAG = "PipkinRemoteHosts"
    const val FIRST_BACKOFF_MILLIS = 30_000L
    const val MAX_BACKOFF_MILLIS = 5 * 60_000L
  }
}

/** What [RemoteHosts.reconnect] did. */
internal sealed interface Reconnect {
  data object Connected : Reconnect

  /** Nothing to sign in with: the host has no saved password, the server refused it, or Termux didn't answer. */
  data object Skipped : Reconnect

  /** The sign-in failed for [failure], now or, while [RemoteHosts] waits to try again, the last time. */
  data class Failed(val failure: RemoteFailure) : Reconnect
}

/** Where installing OpenSSH stands. */
internal sealed interface OpenSshInstall {
  data object Idle : OpenSshInstall

  /** Installing; [line] is `pkg`'s latest output, empty before it says anything. */
  data class Running(val line: String) : OpenSshInstall

  /** The last install failed, for the reason in [error]. */
  data class Failed(val error: String) : OpenSshInstall
}
