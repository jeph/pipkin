package io.jeph.pipkin.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.sameFolder
import io.jeph.pipkin.files.FileTab
import io.jeph.pipkin.git.CommitTab
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.terminal.TerminalSession
import io.jeph.pipkin.workspace.WorkspaceTab

/**
 * The tab menu's tabs for the workspace of a shown chat: its terminals, open files and open commits, oldest first;
 * its saved chats, newest first; whether the shown chat has no saved history yet, which gives it a row of its own;
 * and the tab shown in place of the chat, if any.
 */
internal class WorkspaceTabs(
  val terminals: List<TerminalSession>,
  val files: List<FileTab>,
  val commits: List<CommitTab>,
  val chats: List<SessionInfo>,
  val shownUnsaved: Boolean,
  val shownTab: WorkspaceTab?,
) {
  val count: Int get() = terminals.size + files.size + commits.size + chats.size + if (shownUnsaved) 1 else 0
}

/** The [WorkspaceTabs] of the workspace [process] works in, with [process] as the shown chat. */
@Composable
internal fun rememberWorkspaceTabs(process: AgentProcess): WorkspaceTabs {
  val graph = LocalContext.current.appGraph
  val sessions by graph.sessionArchive.active.collectAsStateWithLifecycle()
  val terminals by graph.terminals.sessions.collectAsStateWithLifecycle()
  val files by graph.files.tabs.collectAsStateWithLifecycle()
  val commits by graph.commits.tabs.collectAsStateWithLifecycle()
  val shown by graph.shownTab.shown.collectAsStateWithLifecycle()
  val shownPath by process.sessionFile.collectAsStateWithLifecycle()
  return remember(sessions, terminals, files, commits, shown, shownPath, process) {
    val chats = sessions.filter { sameFolder(it.cwd, process.cwd) }
    WorkspaceTabs(
      terminals = terminals.filter { sameFolder(it.cwd, process.cwd) },
      files = files.filter { sameFolder(it.cwd, process.cwd) },
      commits = commits.filter { sameFolder(it.cwd, process.cwd) },
      chats = chats,
      shownUnsaved = chats.none { it.path == shownPath },
      shownTab = shown?.takeIf { it.chat == process }?.tab,
    )
  }
}
