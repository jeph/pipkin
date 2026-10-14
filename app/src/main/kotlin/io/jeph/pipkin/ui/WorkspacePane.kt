package io.jeph.pipkin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.chat.ChatScreen
import io.jeph.pipkin.ui.chat.ChatViews
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.files.ExplorerPanel
import io.jeph.pipkin.ui.files.FileScreen
import io.jeph.pipkin.ui.git.CommitScreen
import io.jeph.pipkin.ui.terminal.TerminalScreen
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.PaneItem
import io.jeph.pipkin.workspace.WorkspaceTab

/** One pane's screen for what it shows: a chat, a terminal, a file or a commit, or a placeholder for nothing yet. */
@Composable
internal fun WorkspacePane(
  item: PaneItem<AgentProcess>?,
  actions: ShellActions,
  views: ChatViews,
  controls: PaneControls,
  onOpenSettings: () -> Unit,
) {
  if (item !is PaneItem.Tab) {
    ChatScreen((item as? PaneItem.Chat)?.process, actions, views, controls, onOpenSettings)
    return
  }
  when (val tab = item.tab) {
    is WorkspaceTab.Terminal -> TerminalScreen(tab.session, controls)
    is WorkspaceTab.File -> FileScreen(tab.file, controls)
    is WorkspaceTab.Commit -> CommitScreen(tab.commit, controls)
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
