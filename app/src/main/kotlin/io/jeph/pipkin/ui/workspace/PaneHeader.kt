package io.jeph.pipkin.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.HeaderButton
import io.jeph.pipkin.ui.components.HeaderButtonSize
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PotIcon
import io.jeph.pipkin.ui.components.roundButton
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.pipkinColors
import java.text.NumberFormat

/**
 * The header of a screen in a pane: the pot that toggles the sidebar; the [title]; the tabs button, which opens the
 * session sheet and shows how many tabs the workspace folder [cwd] has; the explorer's toggle; and, in the end-side
 * pane, the button that closes split screen. Under the title is [folder], with `~` for the Termux home, or the
 * [status] extensions set while there is one.
 */
@Composable
internal fun PaneHeader(
  title: String,
  cwd: String,
  controls: PaneControls,
  modifier: Modifier = Modifier,
  folder: String = cwd,
  status: AnnotatedString? = null,
) {
  val hello by LocalContext.current.appGraph.hostClient.lastHello.collectAsStateWithLifecycle()
  val tabs = rememberWorkspaceTabs(controls.pane, cwd).count
  HeaderBar(
    title = title,
    modifier = modifier,
    folder = hello?.home?.let { tildePath(folder, it) } ?: folder,
    status = status,
    leading = { SidebarButton(controls.onToggleSidebar) },
    trailing = {
      TabsButton(tabs, controls.onOpenTabs)
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

/**
 * The button that opens the session sheet: a square with the number of [tabs] in it, as a browser's tab switcher
 * shows, and past [MAX_SHOWN_TABS] that number with a plus. TalkBack reads the whole count.
 */
@Composable
private fun TabsButton(tabs: Int, onClick: () -> Unit) {
  val colors = pipkinColors
  val description = pluralStringResource(R.plurals.shell_tabs_button, tabs, tabs)
  val number = remember(tabs) { NumberFormat.getIntegerInstance().format(tabs.coerceAtMost(MAX_SHOWN_TABS)) }
  val count = if (tabs > MAX_SHOWN_TABS) stringResource(R.string.shell_tabs_more, number) else number
  Box(
    modifier = Modifier
      .roundButton(size = HeaderButtonSize, background = colors.surface2, enabled = true, onClick = onClick)
      .semantics {
        contentDescription = description
        role = Role.Button
      },
    contentAlignment = Alignment.Center,
  ) {
    LucideIcon(
      icon = R.drawable.ic_lucide_square,
      contentDescription = null,
      size = TABS_ICON_SIZE,
      tint = colors.foreground,
    )
    Text(
      text = count,
      modifier = Modifier.clearAndSetSemantics {},
      color = colors.foreground,
      fontSize = if (count.length > 2) 7.sp else 9.sp,
      fontWeight = FontWeight.ExtraBold,
      style = LocalTextStyle.current.copy(fontFeatureSettings = PipkinFonts.TABULAR, platformStyle = NO_FONT_PADDING),
      maxLines = 1,
    )
  }
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
private const val MAX_SHOWN_TABS = 99
private val TABS_ICON_SIZE = 22.dp
private val NO_FONT_PADDING = PlatformTextStyle(includeFontPadding = false)
