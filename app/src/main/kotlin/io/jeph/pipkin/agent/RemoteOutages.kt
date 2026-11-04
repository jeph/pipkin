package io.jeph.pipkin.agent

import io.jeph.pipkin.data.RemoteHosts
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.RemoteFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * A remote chat's host can't be reached; [failure] says why, or is null when `pipkin-remote` didn't say. [hostId] is
 * null while the app's list of stand-in folders doesn't have the chat's yet, as before the first `remote_hosts`.
 */
internal data class RemoteOutage(val hostId: String?, val failure: RemoteFailure?)

/**
 * Whether the remote host of the chat in [cwd] was lost, as its Pi's `pipkin-remote` extension last said over Pipkin's
 * channel ([TOPIC]), or as the host said when Pi couldn't start there. [remoteHosts] learns each of these too, and
 * gives the host the chat's stand-in folder stands for.
 */
internal class RemoteOutages(private val cwd: String, private val remoteHosts: RemoteHosts) {
  /** Not saved: each connection's Pi says so again when a tool call finds the host gone. */
  private val stateFlow = MutableStateFlow<RemoteOutage?>(null)

  /** The chat's remote host while a tool call last found it lost; null while it works, and for a local chat. */
  val state: StateFlow<RemoteOutage?> = stateFlow.asStateFlow()

  /**
   * `pipkin-remote` found the chat's host lost or back on a tool call, which [remoteHosts] learns too when it knows the
   * host; the chat shows it either way, since the host list may not have loaded yet.
   */
  fun onMessage(data: JSONObject) {
    val hostId = hostId()
    if (data.optBoolean("connected")) {
      stateFlow.value = null
      hostId?.let(remoteHosts::noteReached)
      return
    }
    val failure = RemoteFailure.from(data.optJSONObject("error"))
    stateFlow.value = RemoteOutage(hostId, failure)
    if (hostId != null && failure != null) remoteHosts.noteFailure(hostId, failure.code)
  }

  /** The chat's host, when [rejection] says Pi couldn't start because it can't be reached; [remoteHosts] learns so. */
  fun refused(rejection: HostRejected): RemoteOutage? {
    if (!RemoteFailure.isDisconnect(rejection.code)) return null
    val hostId = hostId() ?: return null
    remoteHosts.noteFailure(hostId, rejection.code)
    return RemoteOutage(hostId, RemoteFailure.of(rejection))
  }

  /**
   * Forgets the outage: the Pi that saw it is gone, or the user connected the host again, so the chat stops saying
   * it's lost until a tool call finds it so.
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
    /** The `pipkin-remote` extension's topic: whether the chat's host was reachable on the last tool call. */
    const val TOPIC = "remote"
  }
}
