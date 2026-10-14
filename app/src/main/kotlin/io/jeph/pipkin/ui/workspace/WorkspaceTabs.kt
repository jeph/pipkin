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
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.ShownTabs
import io.jeph.pipkin.workspace.WorkspaceTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The tab menu's tabs for the workspace of a pane's shown chat: its terminals, open files and open commits, oldest
 * first; its saved chats, newest first, which leaves out a new chat until Pi saved its first message; and what the
 * panes show.
 */
internal class WorkspaceTabs(
  val terminals: List<TerminalSession>,
  val files: List<FileTab>,
  val commits: List<CommitTab>,
  val chats: List<SessionInfo>,
  val shown: PanesShow,
) {
  val count: Int get() = terminals.size + files.size + commits.size + chats.size
}

/** Where a tab menu row's tab shows: in no pane, in the menu's own pane, or in the other pane, which keeps it. */
internal enum class TabShown { Nowhere, Here, OtherPane }

/**
 * What the panes show, from one pane's side: the [tab] it shows in place of its chat, if any, and the session file of
 * the chat and the tab the other pane shows, which this pane can't show too.
 */
internal class PanesShow(val tab: WorkspaceTab?, val elsewhereChat: String?, val elsewhereTab: WorkspaceTab?) {
  fun of(tab: WorkspaceTab): TabShown = when (tab) {
    this.tab -> TabShown.Here
    elsewhereTab -> TabShown.OtherPane
    else -> TabShown.Nowhere
  }
}

/** The [WorkspaceTabs] of the workspace [process] works in, with [process] as the chat [pane] shows. */
@Composable
internal fun rememberWorkspaceTabs(pane: Pane, process: AgentProcess): WorkspaceTabs {
  val graph = LocalContext.current.appGraph
  val sessions by graph.sessionArchive.active.collectAsStateWithLifecycle()
  val terminals by graph.terminals.sessions.collectAsStateWithLifecycle()
  val files by graph.files.tabs.collectAsStateWithLifecycle()
  val commits by graph.commits.tabs.collectAsStateWithLifecycle()
  val shown by graph.shownTabs.shown.collectAsStateWithLifecycle()
  val panes by graph.registry.shown.collectAsStateWithLifecycle()
  val elsewhere = panes[pane.other]
  val elsewherePath by remember(elsewhere) { elsewhere?.sessionFile ?: NO_SESSION }.collectAsStateWithLifecycle()
  return remember(sessions, terminals, files, commits, shown, panes, elsewherePath, pane, process) {
    WorkspaceTabs(
      terminals = terminals.filter { sameFolder(it.cwd, process.cwd) },
      files = files.filter { sameFolder(it.cwd, process.cwd) },
      commits = commits.filter { sameFolder(it.cwd, process.cwd) },
      chats = sessions.filter { sameFolder(it.cwd, process.cwd) },
      shown = PanesShow(
        tab = shown[pane]?.takeIf { it.chat === process }?.tab,
        elsewhereChat = elsewherePath,
        elsewhereTab = ShownTabs.shownIn(shown, panes, pane.other),
      ),
    )
  }
}

private val NO_SESSION: StateFlow<String?> = MutableStateFlow(null)
