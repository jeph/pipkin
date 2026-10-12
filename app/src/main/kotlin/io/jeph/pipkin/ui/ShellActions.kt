package io.jeph.pipkin.ui

import android.annotation.SuppressLint
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.ChatKey
import io.jeph.pipkin.agent.RpcError
import io.jeph.pipkin.agent.TooManyRunning
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.SessionInfo
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The shell's chat operations: which chat the chat panel shows, new chats, and the sidebar's
 * rename and delete. Failures surface as [notices] for the shell's snackbar. Main thread only.
 */
internal class ShellActions(private val graph: AppGraph) {
  private val noticeChannel = Channel<String?>(Channel.BUFFERED)
  private val registry get() = graph.registry
  private val sessions get() = graph.sessionRepository

  /** One-shot user messages (snackbar); null is a failure without a message of its own. */
  val notices: Flow<String?> = noticeChannel.receiveAsFlow()

  /** Termux's home from the last host hello; upstream Termux always uses [TERMUX_HOME] before any host ran. */
  val home: String get() = graph.hostClient.lastHello.value?.home?.takeIf { it.isNotEmpty() } ?: TERMUX_HOME

  /** Shows [session]'s chat; false (with a notice) when four chats are working. */
  fun openSession(session: SessionInfo): Boolean = openChat(session.cwd, session.path)

  /** Shows the chat in session file [sessionPath]; false (with a notice) when four chats are working. */
  fun openChat(cwd: String, sessionPath: String): Boolean = show { registry.open(cwd, sessionPath) }

  /** Shows a new chat in [cwd]; false (with a notice) when four chats are working. */
  fun newChat(cwd: String): Boolean = show { registry.open(cwd, null) }

  /** Shows the most recent chat, or a new one in the last project (or home) when there is none. */
  fun openMostRecent() {
    val newest = sessions.sessions.value.firstOrNull()
    if (newest != null) openSession(newest) else newChat(graph.preferences.lastProjectCwd.value ?: home)
  }

  /** On entering the main screen without a chat: waits for the session list, then [openMostRecent]. */
  suspend fun openAtStartup() {
    if (registry.visible.value != null) return
    if (!sessions.loaded.value) sessions.refresh()
    sessions.loaded.first { it }
    if (registry.visible.value == null) openMostRecent()
  }

  /** Renames [session] through its Pi process, starting one in the background if it is not open. */
  fun rename(session: SessionInfo, name: String) {
    graph.scope.launch {
      val process = try {
        registry.open(session.cwd, session.path)
      } catch (error: TooManyRunning) {
        notify(error.message)
        return@launch
      }
      val state = withTimeoutOrNull(START_TIMEOUT_MILLIS) {
        process.state.first { it !is AgentProcessState.Starting && it !is AgentProcessState.Installing }
      }
      if (state !is AgentProcessState.Ready) {
        notify((state as? AgentProcessState.Failed)?.message)
        return@launch
      }
      try {
        process.setSessionName(name)
      } catch (error: RpcError) {
        notify(error.error)
      }
    }
  }

  /** Closes [session]'s process if open and deletes its file; a shown chat is replaced by the most recent one. */
  fun delete(session: SessionInfo) {
    graph.scope.launch {
      val open = registry.processes.value[ChatKey.session(session.path)]
      val wasShown = open != null && registry.visible.value === open
      open?.let(registry::close)
      try {
        sessions.delete(session.path)
      } catch (error: IOException) {
        notify(error.message)
        return@launch
      } catch (error: HostRejected) {
        notify(error.error)
        return@launch
      }
      if (wasShown) openMostRecent()
    }
  }

  private inline fun show(open: () -> AgentProcess): Boolean = try {
    registry.setVisible(open())
    true
  } catch (error: TooManyRunning) {
    notify(error.message)
    false
  }

  private fun notify(message: String?) {
    noticeChannel.trySend(message)
  }

  private companion object {
    // Termux's own fixed home, not a path in Pipkin's storage.
    @SuppressLint("SdCardPath")
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val START_TIMEOUT_MILLIS = 60_000L
  }
}
