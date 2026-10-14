package io.jeph.pipkin.ui.workspace

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.files.FileActionsSheet
import io.jeph.pipkin.ui.files.FileScreen
import io.jeph.pipkin.ui.git.CommitActionsSheet
import io.jeph.pipkin.ui.git.CommitScreen
import io.jeph.pipkin.ui.terminal.TerminalActionsSheet
import io.jeph.pipkin.ui.terminal.TerminalScreen
import io.jeph.pipkin.workspace.WorkspaceTab

// What the UI does differently for each kind of tab. Everything else, from the tab menu's list and count to swapping,
// Back and closing, works on any WorkspaceTab, so a new kind only adds a branch to each `when` here.

/** Where [this] lists in the tab menu: terminals first, then files, then commits, each oldest first. */
internal val WorkspaceTab.menuGroup: Int
  get() = when (this) {
    is WorkspaceTab.Terminal -> 0
    is WorkspaceTab.File -> 1
    is WorkspaceTab.Commit -> 2
  }

/** What closing a tab from the tab menu asks first: a title, a text and the confirm button's label. */
internal class CloseWarning(@StringRes val title: Int, @StringRes val text: Int, @StringRes val confirm: Int)

/** What closing [this] from the tab menu asks first, or null when it closes without asking. */
internal fun WorkspaceTab.closeWarning(): CloseWarning? = when (this) {
  is WorkspaceTab.Terminal -> CloseWarning(
    title = R.string.terminal_close_confirm_title,
    text = R.string.terminal_close_confirm_text,
    confirm = R.string.terminal_close_confirm,
  ).takeIf { session.mayHaveWorkRunning() }

  is WorkspaceTab.File, is WorkspaceTab.Commit -> null
}

/** [tab]'s screen in a pane. */
@Composable
internal fun WorkspaceTabScreen(tab: WorkspaceTab, controls: PaneControls) {
  when (tab) {
    is WorkspaceTab.Terminal -> TerminalScreen(tab.session, controls)
    is WorkspaceTab.File -> FileScreen(tab.file, controls)
    is WorkspaceTab.Commit -> CommitScreen(tab.commit, controls)
  }
}

/** [tab]'s row in the tab menu. */
@Composable
internal fun WorkspaceTabRow(tab: WorkspaceTab, shown: TabShown, onOpen: () -> Unit, onActions: () -> Unit) {
  when (tab) {
    is WorkspaceTab.Terminal -> TerminalTabRow(tab.session, shown, onOpen, onActions)
    is WorkspaceTab.File -> FileTabRow(tab.file, shown, onOpen, onActions)
    is WorkspaceTab.Commit -> CommitTabRow(tab.commit, shown, onOpen, onActions)
  }
}

/** [tab]'s actions sheet in the tab menu, where [onClose] closes it. */
@Composable
internal fun WorkspaceTabActionsSheet(tab: WorkspaceTab, onClose: () -> Unit, onDismiss: () -> Unit) {
  when (tab) {
    is WorkspaceTab.Terminal -> TerminalActionsSheet(
      session = tab.session,
      onNewTerminal = null,
      onRestart = null,
      onClose = onClose,
      onDismiss = onDismiss,
    )

    is WorkspaceTab.File -> FileActionsSheet(file = tab.file, onClose = onClose, onDismiss = onDismiss)

    is WorkspaceTab.Commit -> CommitActionsSheet(onClose = onClose, onDismiss = onDismiss)
  }
}
