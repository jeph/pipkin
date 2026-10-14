package io.jeph.pipkin.workspace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Every open tab of every workspace, oldest first. Each kind's registry opens its tabs through [show], so the tab menu
 * lists them and [close] closes them without knowing their kind. Main thread only.
 */
internal class OpenTabs(private val panes: Panes<*>) {
  private val tabsFlow = MutableStateFlow<List<WorkspaceTab>>(emptyList())

  val tabs: StateFlow<List<WorkspaceTab>> = tabsFlow.asStateFlow()

  /** Shows [tab] in [pane], opening it when it isn't open. False, changing nothing, when the other pane shows it. */
  fun show(pane: Pane, tab: WorkspaceTab): Boolean {
    if (tab !in tabsFlow.value) tabsFlow.update { it + tab }
    return panes.showTab(pane, tab)
  }

  /** Opens [restored] tabs from before a restart, without showing them, ahead of any opened since. */
  fun restore(restored: List<WorkspaceTab>) {
    tabsFlow.update { open -> restored.filterNot { it in open } + open }
  }

  /**
   * Closes [tab]: the pane showing it goes back, every history forgets it, and it lets go of what it holds. The UI
   * closes through `ShellActions.closeTab`, which also gives a pane left with nothing a chat.
   */
  fun close(tab: WorkspaceTab) {
    if (tab !in tabsFlow.value) return
    tabsFlow.update { it - tab }
    panes.forget(tab)
    tab.release()
  }
}
