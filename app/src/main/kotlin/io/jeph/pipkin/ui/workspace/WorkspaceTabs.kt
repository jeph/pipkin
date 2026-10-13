package io.jeph.pipkin.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.sameFolder
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.terminal.TerminalSession

/**
 * The tab menu's tabs for the workspace of a shown chat: its terminals, oldest first; its saved chats, newest first;
 * whether the shown chat has no saved history yet, which gives it a row of its own; and the terminal shown in place of
 * the chat, if any.
 */
internal class WorkspaceTabs(
  val terminals: List<TerminalSession>,
  val chats: List<SessionInfo>,
  val shownUnsaved: Boolean,
  val shownTerminal: TerminalSession?,
) {
  val count: Int get() = terminals.size + chats.size + if (shownUnsaved) 1 else 0
}

/** The [WorkspaceTabs] of the workspace [process] works in, with [process] as the shown chat. */
@Composable
internal fun rememberWorkspaceTabs(process: AgentProcess): WorkspaceTabs {
  val graph = LocalContext.current.appGraph
  val sessions by graph.sessionArchive.active.collectAsStateWithLifecycle()
  val terminals by graph.terminals.sessions.collectAsStateWithLifecycle()
  val shown by graph.terminals.shown.collectAsStateWithLifecycle()
  val shownPath by process.sessionFile.collectAsStateWithLifecycle()
  return remember(sessions, terminals, shown, shownPath, process) {
    val chats = sessions.filter { sameFolder(it.cwd, process.cwd) }
    WorkspaceTabs(
      terminals = terminals.filter { sameFolder(it.cwd, process.cwd) },
      chats = chats,
      shownUnsaved = chats.none { it.path == shownPath },
      shownTerminal = shown?.takeIf { it.chat == process }?.session,
    )
  }
}
