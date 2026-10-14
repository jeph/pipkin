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
import io.jeph.pipkin.workspace.PaneItem
import io.jeph.pipkin.workspace.Panes
import io.jeph.pipkin.workspace.WorkspaceTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The tab menu's tabs for a workspace: its terminals, open files and open commits, oldest first; its saved chats,
 * newest first, which leaves out a new chat until Pi saved its first message; and what the panes show.
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
 * What the panes show, from one pane's side: the session file of the chat or the [tab] it shows, and the session file
 * of the chat or the tab the other pane shows, which this pane can't show too.
 */
internal class PanesShow(
  val chat: String?,
  val tab: WorkspaceTab?,
  val elsewhereChat: String?,
  val elsewhereTab: WorkspaceTab?,
) {
  fun of(tab: WorkspaceTab): TabShown = when (tab) {
    this.tab -> TabShown.Here
    elsewhereTab -> TabShown.OtherPane
    else -> TabShown.Nowhere
  }
}

/** By pane, what it shows. */
@Composable
internal fun rememberPaneItems(): Map<Pane, PaneItem<AgentProcess>> {
  val graph = LocalContext.current.appGraph
  val tabs by graph.panes.tabs.collectAsStateWithLifecycle()
  val chats by graph.registry.shown.collectAsStateWithLifecycle()
  return remember(tabs, chats) {
    Pane.entries.mapNotNull { pane -> Panes.itemIn(tabs, chats, pane)?.let { pane to it } }.toMap()
  }
}

/** The [WorkspaceTabs] of the workspace folder [cwd], seen from [pane]. */
@Composable
internal fun rememberWorkspaceTabs(pane: Pane, cwd: String): WorkspaceTabs {
  val graph = LocalContext.current.appGraph
  val sessions by graph.sessionArchive.active.collectAsStateWithLifecycle()
  val terminals by graph.terminals.sessions.collectAsStateWithLifecycle()
  val files by graph.files.tabs.collectAsStateWithLifecycle()
  val commits by graph.commits.tabs.collectAsStateWithLifecycle()
  val items = rememberPaneItems()
  val here = items[pane]
  val elsewhere = items[pane.other]
  val herePath by sessionFileOf(here).collectAsStateWithLifecycle()
  val elsewherePath by sessionFileOf(elsewhere).collectAsStateWithLifecycle()
  return remember(sessions, terminals, files, commits, here, elsewhere, herePath, elsewherePath, cwd) {
    WorkspaceTabs(
      terminals = terminals.filter { sameFolder(it.cwd, cwd) },
      files = files.filter { sameFolder(it.cwd, cwd) },
      commits = commits.filter { sameFolder(it.cwd, cwd) },
      chats = sessions.filter { sameFolder(it.cwd, cwd) },
      shown = PanesShow(
        chat = herePath,
        tab = (here as? PaneItem.Tab)?.tab,
        elsewhereChat = elsewherePath,
        elsewhereTab = (elsewhere as? PaneItem.Tab)?.tab,
      ),
    )
  }
}

/** The session file of [item]'s chat; null for a tab or nothing. */
@Composable
private fun sessionFileOf(item: PaneItem<AgentProcess>?): StateFlow<String?> =
  remember(item) { (item as? PaneItem.Chat)?.process?.sessionFile ?: NO_SESSION }

private val NO_SESSION: StateFlow<String?> = MutableStateFlow(null)
