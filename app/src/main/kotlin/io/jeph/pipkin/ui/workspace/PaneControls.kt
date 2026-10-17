package io.jeph.pipkin.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.WorkspaceTab

/** What the screens a pane shows do from their headers, for the [pane] they show in. */
internal class PaneControls(
  val pane: Pane,
  val onToggleSidebar: () -> Unit,
  val onOpenTabs: () -> Unit,
  val onToggleExplorer: () -> Unit,
  /** Closes a tab, as from its actions sheet. */
  val onCloseTab: (WorkspaceTab) -> Unit,
  /** Closes split screen from this pane; null unless split screen shows this pane on the end side. */
  val onCloseSplit: (() -> Unit)?,
)

/** The end-side pane's button that closes split screen, at the end of its screens' headers. */
@Composable
internal fun CloseSplitButton(controls: PaneControls) {
  val onClose = controls.onCloseSplit ?: return
  IconButton28(
    icon = R.drawable.ic_lucide_x,
    contentDescription = stringResource(R.string.shell_split_close),
    onClick = onClose,
  )
}

/**
 * Whether the window is wide and tall enough for two panes side by side, as an open foldable or a tablet is: at least
 * Material's medium width (600dp) and medium height (480dp). A phone held sideways is wide but too short.
 */
@Composable
@ReadOnlyComposable
internal fun splitScreenAvailable(): Boolean {
  val size = LocalWindowInfo.current.containerDpSize
  return size.width >= 600.dp && size.height >= 480.dp
}
