package io.jeph.pipkin.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.chat.ChatScreen
import io.jeph.pipkin.ui.chat.ChatViews
import io.jeph.pipkin.ui.chat.rememberPaneChats
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.files.ExplorerPanel
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.WorkspaceTabScreen
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.PaneItem
import io.jeph.pipkin.workspace.Panes
import io.jeph.pipkin.workspace.WorkspaceTab
import io.jeph.pipkin.workspace.sameAs

/**
 * One pane's screen for what it shows: a chat, a terminal, a file or a commit, or a placeholder for nothing yet. The
 * pane stacks its screens as its history does: what opens over the shown item slides in from the right, and what
 * leaves for an item Back returns to, or for the one replacing a closed tab or an archived chat, slides back out over
 * it. While [covered] by a side panel, whose own slide brings the pane back, the screen changes in place.
 */
@Composable
internal fun WorkspacePane(
  item: PaneItem<AgentProcess>?,
  covered: Boolean,
  actions: ShellActions,
  views: ChatViews,
  controls: PaneControls,
  onOpenSettings: () -> Unit,
  onOpenProviders: () -> Unit,
  onOpenOnboarding: () -> Unit,
) {
  val panes = LocalContext.current.appGraph.panes
  val stack = remember(panes, controls.pane) { PaneStack(panes, controls.pane, item) }
  val screen = stack.screenFor(item)
  val paneChats = rememberPaneChats()
  val latestCovered by rememberUpdatedState(covered)
  AnimatedContent(
    targetState = screen,
    transitionSpec = {
      val (from, to) = initialState.depth to targetState.depth
      if (latestCovered || from == to) {
        (EnterTransition.None togetherWith ExitTransition.None).apply { targetContentZIndex = to.toFloat() }
      } else {
        stackTransition(from, to)
      }
    },
    contentKey = { it.item },
    label = "pane",
  ) { shown ->
    val leavingControls = remember(controls) { controls.forLeavingScreen() }
    val screenControls = if (transition.targetState == EnterExitState.Visible) controls else leavingControls
    when (val shownItem = shown.item) {
      is PaneItem.Tab -> WorkspaceTabScreen(shownItem.tab, screenControls)

      is PaneItem.Chat, null -> ChatScreen(
        shownItem?.process,
        paneChats,
        actions,
        views,
        screenControls,
        onOpenSettings,
        onOpenProviders,
        onOpenOnboarding,
      )
    }
  }
}

/** An item a pane shows, at its [depth] in the pane's stack of screens. */
private data class PaneScreen(val item: PaneItem<AgentProcess>?, val depth: Int)

/**
 * [pane]'s stack depth as its item changes: one deeper for an item opened over the one the pane's history now keeps,
 * one shallower for any other, as Back, a closed tab or an archived chat; the same for the same chat reloaded, and to
 * or from nothing shown.
 */
private class PaneStack(private val panes: Panes<AgentProcess>, private val pane: Pane, item: PaneItem<AgentProcess>?) {
  private var shown = PaneScreen(item, depth = 0)

  fun screenFor(item: PaneItem<AgentProcess>?): PaneScreen {
    val previous = shown.item
    if (item == previous) return shown
    // Read rather than observed: the call that changed the item changed the history with it, before the pane
    // recomposed, so it says where the item came from.
    val history = panes.history.value[pane].orEmpty()
    val depth = when {
      previous == null || item == null || item.sameAs(previous) -> shown.depth
      previous in history -> shown.depth + 1
      else -> shown.depth - 1
    }
    shown = PaneScreen(item, depth)
    return shown
  }
}

/**
 * Tells the dialog notifications and the chat inbox which [chats] the user sees: those shown in a pane with no side
 * panel over them, while Pipkin is on screen and no other screen covers the panes.
 */
@Composable
internal fun ChatsOnScreen(chats: Set<AgentProcess>) {
  val chatsOnScreen = LocalContext.current.appGraph.chatsOnScreen
  LifecycleStartEffect(chats) {
    chatsOnScreen.value = chats
    onStopOrDispose { chatsOnScreen.value = emptySet() }
  }
}

/**
 * [pane]'s explorer, for the folder of [item], what the pane shows. Opening a file or a commit shows it in the pane
 * and slides back to it, as Paseo's compact explorer does; the shown file's row is highlighted. One the other pane
 * shows stays there, and [onSwapPanes] brings that pane to this side.
 */
@Composable
internal fun PaneExplorer(
  pane: Pane,
  item: PaneItem<AgentProcess>?,
  open: Boolean,
  snackbar: SnackbarStackState,
  onSwapPanes: () -> Unit,
  onClose: () -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val cwd = item?.cwd
  val shownFile = ((item as? PaneItem.Tab)?.tab as? WorkspaceTab.File)?.file
  val closeOrSwap = { opened: Boolean ->
    if (!opened) onSwapPanes()
    onClose()
  }
  ExplorerPanel(
    cwd = cwd,
    open = open,
    selectedPath = shownFile?.path,
    snackbar = snackbar,
    onClose = onClose,
    onOpenFile = { path -> cwd?.let { closeOrSwap(graph.files.open(pane, it, path)) } },
    onOpenCommit = { commit -> cwd?.let { closeOrSwap(graph.commits.open(pane, it, commit)) } },
  )
}
