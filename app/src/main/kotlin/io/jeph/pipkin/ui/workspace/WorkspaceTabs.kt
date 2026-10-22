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
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.PaneItem
import io.jeph.pipkin.workspace.Panes
import io.jeph.pipkin.workspace.WorkspaceTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The tab menu's tabs for a workspace: its open tabs, by [menuGroup] and then oldest first; its saved chats, newest
 * first, which leaves out a new chat until Pi saved its first message; and what the panes show. What this side's pane
 * shows comes first, so the menu opens on it: the shown tab heads [tabs], and the shown chat, which the menu lists
 * before the tabs, is [shownChat] and heads [chats].
 */
internal class WorkspaceTabs(val tabs: List<WorkspaceTab>, val chats: List<SessionInfo>, val shown: PanesShow) {
  val count: Int get() = tabs.size + chats.size

  /** The saved chat this side's pane shows, if it's one of [chats]. */
  val shownChat: SessionInfo? = chats.firstOrNull()?.takeIf { it.path == shown.chat }

  /** [chats] without [shownChat]. */
  val otherChats: List<SessionInfo> = if (shownChat == null) chats else chats.drop(1)
}

/**
 * What the panes show, from one pane's side: the session file of the chat or the [tab] it shows, and the session file
 * of the chat the other pane shows, which opening from this pane swaps over.
 */
internal class PanesShow(val chat: String?, val tab: WorkspaceTab?, val elsewhereChat: String?)

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
  val open by graph.tabs.tabs.collectAsStateWithLifecycle()
  val items = rememberPaneItems()
  val here = items[pane]
  val elsewhere = items[pane.other]
  val herePath by sessionFileOf(here).collectAsStateWithLifecycle()
  val elsewherePath by sessionFileOf(elsewhere).collectAsStateWithLifecycle()
  return remember(sessions, open, here, elsewhere, herePath, elsewherePath, cwd) {
    val shownTab = (here as? PaneItem.Tab)?.tab
    WorkspaceTabs(
      tabs = open.filter { sameFolder(it.cwd, cwd) }.sortedBy { it.menuGroup }.withFirst { it == shownTab },
      chats = sessions.filter { sameFolder(it.cwd, cwd) }.withFirst { it.path == herePath },
      shown = PanesShow(
        chat = herePath,
        tab = shownTab,
        elsewhereChat = elsewherePath,
      ),
    )
  }
}

/** This list with its first element that [isFirst] moved to the front, keeping the others' order. */
private fun <T> List<T>.withFirst(isFirst: (T) -> Boolean): List<T> {
  val index = indexOfFirst(isFirst)
  if (index <= 0) return this
  return buildList(size) {
    add(this@withFirst[index])
    addAll(this@withFirst.subList(0, index))
    addAll(this@withFirst.subList(index + 1, this@withFirst.size))
  }
}

/** The session file of [item]'s chat; null for a tab or nothing. */
@Composable
private fun sessionFileOf(item: PaneItem<AgentProcess>?): StateFlow<String?> =
  remember(item) { (item as? PaneItem.Chat)?.process?.sessionFile ?: NO_SESSION }

private val NO_SESSION: StateFlow<String?> = MutableStateFlow(null)
