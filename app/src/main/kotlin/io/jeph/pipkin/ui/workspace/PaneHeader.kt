package io.jeph.pipkin.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.HeaderButton
import io.jeph.pipkin.ui.components.HeaderButtonSize
import io.jeph.pipkin.ui.components.PotIcon
import io.jeph.pipkin.ui.components.TitleBadge
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.pipkinColors
import java.text.NumberFormat

/**
 * The header of a screen in a pane: the pot that toggles the sidebar; the [title] as the button that opens the tab
 * menu, with the number of tabs in the workspace folder [cwd]; the screen's actions; the explorer's toggle; and, in
 * the end-side pane, the button that closes split screen. Under the title is [folder], with `~` for the Termux home,
 * or the [status] extensions set while there is one.
 */
@Composable
internal fun PaneHeader(
  title: String,
  cwd: String,
  controls: PaneControls,
  actionsDescription: String,
  onActions: () -> Unit,
  modifier: Modifier = Modifier,
  folder: String = cwd,
  status: AnnotatedString? = null,
) {
  val hello by LocalContext.current.appGraph.hostClient.lastHello.collectAsStateWithLifecycle()
  val tabs = rememberWorkspaceTabs(controls.pane, cwd).count
  val count = remember(tabs) { NumberFormat.getIntegerInstance().format(tabs) }
  HeaderBar(
    title = title,
    modifier = modifier,
    folder = hello?.home?.let { tildePath(folder, it) } ?: folder,
    status = status,
    badge = TitleBadge(count, pluralStringResource(R.plurals.shell_tabs_button, tabs, tabs)),
    onTitleClick = controls.onOpenTabs,
    leading = { SidebarButton(controls.onToggleSidebar) },
    trailing = {
      HeaderButton(icon = R.drawable.ic_lucide_ellipsis, contentDescription = actionsDescription, onClick = onActions)
      HeaderButton(
        icon = R.drawable.ic_lucide_panel_right,
        contentDescription = stringResource(R.string.files_explorer_open),
        onClick = controls.onToggleExplorer,
        filled = true,
      )
      CloseSplitButton(controls)
    },
  )
}

/** The pot at the start of a pane's header, which toggles the sidebar. */
@Composable
internal fun SidebarButton(onToggleSidebar: () -> Unit) {
  val description = stringResource(R.string.chat_toggle_sidebar)
  Box(
    modifier = Modifier
      .size(HeaderButtonSize)
      .clip(POT_SHAPE)
      .background(pipkinColors.surface2)
      .clickable(role = Role.Button, onClick = onToggleSidebar)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    PotIcon(size = POT_SIZE)
  }
}

private val POT_SHAPE = RoundedCornerShape(13.dp)
private val POT_SIZE = 24.dp
