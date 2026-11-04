package io.jeph.pipkin.data

import android.util.Log
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.RemoteConnectUi
import io.jeph.pipkin.host.RemoteFailure
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.host.RemoteHostsState
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    load()
  }

  /**
   * Connects host [id], asking [ui] what `ssh` needs. Cancelling the caller, or a null answer, ends it; a null answer
   * throws [HostRejected] with [RemoteFailure.CONNECT_CANCELLED].
   */
  suspend fun connect(id: String, ui: RemoteConnectUi) {
    try {
      keepAlive.acquire("remote connect").use { hostClient.connectRemote(id, ui) }
    } catch (rejected: HostRejected) {
      noteFailure(id, rejected.code)
      throw rejected
    }
    connectedState.update { it + (id to true) }
    hostKeyChangedState.update { it - id }
    // The first connect learns the host's home folder.
    load()
  }

  suspend fun disconnect(id: String) {
    hostClient.remoteDisconnect(id)
    connectedState.update { it + (id to false) }
  }

  suspend fun forgetHostKey(id: String) {
    hostClient.remoteForgetHostKey(id)
    hostKeyChangedState.update { it - id }
  }

  /** Pipkin's public key line, made first if needed. */
  suspend fun publicKey(): String {
    state.value.publicKey?.let { return it }
    val key = hostClient.remoteKey()
    publish(state.value.copy(publicKey = key))
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
  }
}

/** Where installing OpenSSH stands. */
internal sealed interface OpenSshInstall {
  data object Idle : OpenSshInstall

  /** Installing; [line] is `pkg`'s latest output, empty before it says anything. */
  data class Running(val line: String) : OpenSshInstall

  /** The last install failed, for the reason in [error]. */
  data class Failed(val error: String) : OpenSshInstall
}
