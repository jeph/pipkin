package io.jeph.pipkin.workspace

import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.files.FileTab
import io.jeph.pipkin.git.CommitTab
import io.jeph.pipkin.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A workspace tab other than a chat, which the workspace shows in place of its chat. */
internal sealed interface WorkspaceTab {
  data class Terminal(val session: TerminalSession) : WorkspaceTab

  data class File(val file: FileTab) : WorkspaceTab

  data class Commit(val commit: CommitTab) : WorkspaceTab
}

/**
 * The one tab each pane shows in place of a chat, so no two of a workspace's terminals, files and commits can claim
 * a chat's place at once, and no tab shows in two panes: a terminal's view can draw in one place only. A pane shows its
 * tab while [chats] has it showing the chat the tab was shown with. Main-thread confined.
 */
internal class ShownTabs(private val chats: StateFlow<Map<Pane, AgentProcess>>) {
  private val shownFlow = MutableStateFlow<Map<Pane, Shown>>(emptyMap())

  /** By pane, the tab shown instead of [Shown.chat]; a pane showing another chat shows the chat. */
  val shown: StateFlow<Map<Pane, Shown>> = shownFlow.asStateFlow()

  /**
   * Shows [tab] in place of [chat] in [pane], or the chat itself when [tab] is null. Returns false, changing nothing,
   * when the other pane shows [tab].
   */
  fun show(pane: Pane, chat: AgentProcess, tab: WorkspaceTab?): Boolean {
    if (tab == null) {
      shownFlow.update { it - pane }
      return true
    }
    if (shownIn(shownFlow.value, chats.value, pane.other) == tab) return false
    // The other pane may still hold the tab with a chat it no longer shows; showing that chat again must not show it.
    shownFlow.update { panes -> panes.filterValues { it.tab != tab } + (pane to Shown(chat, tab)) }
    return true
  }

  /** Shows the chat again in any pane where [tab], which is closing, is the one shown. */
  fun forget(tab: WorkspaceTab) {
    shownFlow.update { panes -> panes.filterValues { it.tab != tab } }
  }

  /** Forgets [pane]'s tab, as when split screen closes. */
  fun clear(pane: Pane) {
    shownFlow.update { it - pane }
  }

  data class Shown(val chat: AgentProcess, val tab: WorkspaceTab)

  companion object {
    /** The tab [pane] shows, given every pane's [shown] tab and its [chats]. */
    fun shownIn(shown: Map<Pane, Shown>, chats: Map<Pane, AgentProcess>, pane: Pane): WorkspaceTab? =
      shown[pane]?.takeIf { it.chat === chats[pane] }?.tab
  }
}
