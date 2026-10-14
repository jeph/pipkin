package io.jeph.pipkin.workspace

import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.TooManyRunning
import io.jeph.pipkin.host.GitCommit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Keeps the open tabs and what each pane shows across restarts. A chat comes back on its session file, or as a new chat
 * in its folder when Pi hadn't named one; a terminal comes back under its name with a new shell; a commit only while
 * its folder is still trusted. What each side of split screen showed comes back on that side, with the start side's
 * pane as the main one; each pane's Back history starts afresh. Main thread only.
 */
internal class WorkspaceLayout(private val graph: AppGraph) {
  private val tabs get() = graph.tabs
  private val panes get() = graph.panes
  private val registry get() = graph.registry
  private val swappedFlow = MutableStateFlow(false)
  private var restoring: Job? = null

  /** Whether the second pane shows on the start side, before the main pane, as the screen last said. */
  fun setPanesSwapped(swapped: Boolean) {
    swappedFlow.value = swapped
  }

  /**
   * Once the chat list that names restored chats has loaded, opens the saved tabs and shows the saved items in the
   * panes that show nothing yet, then saves the layout whenever it changes; returns once they show. Only the first call
   * restores, and it finishes even when its caller is cancelled. Saving waits until now so the empty layout of a
   * starting process never replaces the saved one.
   */
  suspend fun restore() {
    val job = restoring ?: graph.scope.launch {
      graph.sessionRepository.loaded.first { it }
      graph.preferences.savedLayout()?.let(::show)
      graph.scope.launch { changes().collect(graph.preferences::setSavedLayout) }
    }.also { restoring = it }
    job.join()
  }

  private fun show(layout: SavedLayout) {
    val restoredTabs = layout.tabs.map(::tabFor)
    tabs.restore(restoredTabs.filterNotNull())
    for (pane in Pane.entries) {
      if (panes.itemIn(pane) != null) continue
      when (val item = layout.panes[pane]) {
        is SavedPaneItem.Tab -> restoredTabs[item.index]?.let { tabs.show(pane, it) }
        is SavedPaneItem.Chat -> showChat(pane, item)
        null -> Unit
      }
    }
  }

  private fun tabFor(saved: SavedTab): WorkspaceTab? = when (saved) {
    is SavedTab.Terminal -> graph.terminals.restoredTab(saved.cwd, saved.number, saved.name)
    is SavedTab.File -> graph.files.tabFor(saved.cwd, saved.path)
    is SavedTab.Commit -> graph.commits.restoredTab(saved.cwd, GitCommit(saved.sha, saved.subject, saved.authorTime))
  }

  private fun showChat(pane: Pane, saved: SavedPaneItem.Chat) {
    val process = try {
      registry.open(saved.cwd, saved.sessionPath)
    } catch (_: TooManyRunning) {
      return
    }
    if (registry.show(pane, process)) panes.chatShown(pane, process, previous = null)
  }

  /** The layout each time it changes: tabs open or close, panes show something else or swap, or a name changes. */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun changes(): Flow<SavedLayout> = merge(
    tabs.tabs,
    panes.tabs,
    registry.shown,
    swappedFlow,
    tabs.tabs.flatMapLatest { open -> open.filterIsInstance<WorkspaceTab.Terminal>().map { it.session.name }.merge() },
    // A new chat's session file arrives once Pi has started.
    registry.shown.flatMapLatest { shown -> shown.values.map { it.sessionFile }.merge() },
  )
    .map { snapshot() }
    .distinctUntilChanged()

  private fun snapshot(): SavedLayout {
    val open = tabs.tabs.value
    val items = Pane.entries.mapNotNull { pane -> panes.itemIn(pane)?.saved(open)?.let { pane to it } }.toMap()
    // Saved by side, so the start side's pane comes back as the main pane. The screen only reports the swap a moment
    // after split screen closes, so a lone main pane is never moved to the second pane's slot.
    val swapped = swappedFlow.value && items.size == Pane.entries.size
    return SavedLayout(
      tabs = open.map { it.saved() },
      panes = items.mapKeys { (pane) -> if (swapped) pane.other else pane },
    )
  }
}

private fun WorkspaceTab.saved(): SavedTab = when (this) {
  is WorkspaceTab.Terminal -> SavedTab.Terminal(session.cwd, session.number, session.name.value)
  is WorkspaceTab.File -> SavedTab.File(file.cwd, file.path)
  is WorkspaceTab.Commit -> commit.commit.let { SavedTab.Commit(commit.cwd, it.sha, it.subject, it.authorTime) }
}

private fun PaneItem<AgentProcess>.saved(open: List<WorkspaceTab>): SavedPaneItem? = when (this) {
  is PaneItem.Tab -> open.indexOf(tab).takeIf { it >= 0 }?.let(SavedPaneItem::Tab)
  is PaneItem.Chat -> SavedPaneItem.Chat(process.cwd, process.sessionFile.value)
}
