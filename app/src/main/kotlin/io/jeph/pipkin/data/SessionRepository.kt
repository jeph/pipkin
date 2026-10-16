package io.jeph.pipkin.data

import android.util.Log
import io.jeph.pipkin.agent.ExtensionWidget
import io.jeph.pipkin.agent.KeptBySession
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.SessionInfo
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException

/**
 * Pi's session list, newest first. Refreshed on app foreground, when the sidebar or a tab menu opens, when a new
 * chat's first message creates its session file, after a run settles and after renames and deletes. A failed refresh
 * keeps the previous list; the host failure is reported through [HostClient.lastFailure].
 */
internal class SessionRepository(
  private val hostClient: HostClient,
  /** Each session's extension notices, which Pi does not keep. */
  val notices: NoticeStore,
  scope: CoroutineScope,
) {
  private val sessionsState = MutableStateFlow<List<SessionInfo>>(emptyList())
  private val loadedState = MutableStateFlow(false)
  private val requests = Channel<Unit>(Channel.CONFLATED)

  /** The extension titles of sessions whose Pi stopped, which a later Pi on the session starts with. */
  val extensionTitles = KeptBySession<String>()

  /** The extension statuses of sessions whose Pi stopped, by key, which a later Pi on the session starts with. */
  val extensionStatuses = KeptBySession<Map<String, String>>()

  /** The extension widgets of sessions whose Pi stopped, by key, which a later Pi on the session starts with. */
  val extensionWidgets = KeptBySession<Map<String, ExtensionWidget>>()

  val sessions: StateFlow<List<SessionInfo>> = sessionsState.asStateFlow()

  /** True once the first refresh finished, successfully or not. */
  val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

  init {
    scope.launch { requests.receiveAsFlow().collect { load() } }
  }

  /** Requests a refresh; requests made while one runs coalesce into a single follow-up refresh. */
  fun refresh() {
    requests.trySend(Unit)
  }

  /** Refreshes when [path], a session Pi just saved a message to, isn't listed yet. */
  fun refreshIfUnlisted(path: String) {
    if (sessionsState.value.none { it.path == path }) refresh()
  }

  /**
   * Deletes the session files and their saved notices; throws [HostFailure][io.jeph.pipkin.host.HostFailure] or
   * [HostRejected].
   */
  suspend fun delete(paths: Collection<String>) {
    hostClient.deleteSessions(paths.toList())
    notices.delete(paths)
    sessionsState.value = sessionsState.value.filterNot { it.path in paths }
    refresh()
  }

  /** The session's messages for a read-only transcript; throws like [delete], or [JSONException]. */
  suspend fun messages(path: String): JSONArray = hostClient.sessionMessages(path)

  private suspend fun load() {
    try {
      sessionsState.value = hostClient.sessions()
    } catch (error: IOException) {
      Log.w(TAG, "Refreshing sessions failed", error)
    } catch (error: HostRejected) {
      Log.w(TAG, "Refreshing sessions refused: ${error.code}")
    } catch (error: JSONException) {
      Log.w(TAG, "Refreshing sessions returned malformed data", error)
    } finally {
      loadedState.value = true
    }
  }

  private companion object {
    const val TAG = "Pipkin"
  }
}
