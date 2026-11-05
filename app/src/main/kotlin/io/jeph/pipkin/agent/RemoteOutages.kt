package io.jeph.pipkin.agent

import io.jeph.pipkin.data.Reconnect
import io.jeph.pipkin.data.RemoteHosts
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.RemoteFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A remote chat's host can't be reached; [failure] says why, or is null when `pipkin-remote` didn't say. [hostId] is
 * null while the app's list of stand-in folders doesn't have the chat's yet, as before the first `remote_hosts`.
 */
internal data class RemoteOutage(val hostId: String?, val failure: RemoteFailure?)

/**
 * Whether the remote host of the chat in [cwd] was lost, as its Pi's `pipkin-remote` extension last said over Pipkin's
 * channel ([TOPIC]) after a check or a tool call, or as the host said when Pi couldn't start there. [remoteHosts]
 * learns each of these too, and gives the host the chat's stand-in folder stands for. A host with a saved password is
 * signed in again in [scope], and [send] tells the extension so on [TOPIC].
 */
internal class RemoteOutages(
  private val cwd: String,
  private val remoteHosts: RemoteHosts,
  private val scope: CoroutineScope,
  private val send: suspend (JSONObject) -> Unit,
) {
  /** Not saved: each connection's Pi says so again at its first check. */
  private val stateFlow = MutableStateFlow<RemoteOutage?>(null)

  /** The chat's remote host while it was last found lost; null while it works, and for a local chat. */
  val state: StateFlow<RemoteOutage?> = stateFlow.asStateFlow()

  /**
   * `pipkin-remote` found the chat's host lost or back, on a check or a tool call, which [remoteHosts] learns too when
   * it knows the host; the chat shows it either way, since the host list may not have loaded yet. A call that needs the
   * host signed in again gets the answer to its `reconnect`; a check that found it signed out is checked again once it's
   * signed in, which takes the outage down.
   */
  fun onMessage(data: JSONObject) {
    if (data.optBoolean("reconnect")) {
      scope.launch { send(JSONObject().put("action", "reconnected").put("connected", reconnect())) }
      return
    }
    val hostId = hostId()
    if (data.optBoolean("connected")) {
      stateFlow.value = null
      hostId?.let(remoteHosts::noteReached)
      return
    }
    val failure = RemoteFailure.from(data.optJSONObject("error"))
    stateFlow.value = RemoteOutage(hostId, failure)
    if (hostId == null || failure == null) return
    remoteHosts.noteFailure(hostId, failure.code)
    if (failure.code != RemoteFailure.NOT_CONNECTED) return
    scope.launch {
      if (reconnect()) send(JSONObject().put("action", "check"))
    }
  }

  /** The chat's host, when [rejection] says Pi couldn't start because it can't be reached; [remoteHosts] learns so. */
  fun refused(rejection: HostRejected): RemoteOutage? {
    if (!RemoteFailure.isDisconnect(rejection.code)) return null
    val hostId = hostId() ?: return null
    remoteHosts.noteFailure(hostId, rejection.code)
    return RemoteOutage(hostId, RemoteFailure.of(rejection))
  }

  /**
   * Signs the chat's host in again with its saved password ([RemoteHosts.reconnect]); whether it's connected now. False
   * for a host without one, which only the user can sign in.
   */
  suspend fun reconnect(): Boolean {
    val hostId = hostId() ?: return false
    return remoteHosts.reconnect(hostId) == Reconnect.Connected
  }

  /**
   * Forgets the outage: the Pi that saw it is gone, or the user connected the host again, so the chat stops saying
   * it's lost until a check or a tool call finds it so.
   */
  fun clear() {
    stateFlow.value = null
  }

  /** The host whose folder the chat's stand-in folder stands for; null for a folder on the phone. */
  private fun hostId(): String? {
    val key = folderKey(cwd)
    return remoteHosts.state.value.workspaces.firstOrNull { folderKey(it.cwd) == key }?.hostId
  }

  companion object {
    /**
     * The `pipkin-remote` extension's topic: from it, whether the chat's host was reachable on the last check or tool
     * call, and `{ reconnect: true }` when a call needs a password host signed in again; to it, `{ action: "check" }`
     * ([AgentProcess.checkRemote]) and `{ action: "reconnected", connected }`, the answer to a reconnect.
     */
    const val TOPIC = "remote"
  }
}
