package io.jeph.pipkin.ui.sidebar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.StatusDot
import io.jeph.pipkin.ui.theme.PipkinColors
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.text.BreakIterator

/**
 * A workspace's row: a letter tile in the workspace's colour (the simmering pot while Pi works in one of its chats),
 * its name over its [status], and its actions and New chat. The row is outlined in cobalt while the sidebar's pane
 * shows one of its chats or tabs. Tapping it shows the workspace; long-press opens its actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceRow(
  name: String,
  folder: String,
  cwd: String,
  shown: Boolean,
  status: WorkspaceStatus,
  onOpen: () -> Unit,
  onNewChat: () -> Unit,
  onActions: () -> Unit,
) {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp)
      .clip(ROW_SHAPE)
      .then(
        if (shown) {
          Modifier.background(colors.surface1).border(1.5.dp, colors.accent.copy(alpha = SHOWN_BORDER_ALPHA), ROW_SHAPE)
        } else {
          Modifier
        },
      )
      .combinedClickable(
        onClickLabel = stringResource(R.string.shell_workspace_open, name),
        onClick = onOpen,
        onLongClick = onActions,
      )
      .heightIn(min = 56.dp)
      .padding(
        start = 10.dp,
        end = PipkinTokens.Spacing.s4,
        top = PipkinTokens.Spacing.s8,
        bottom = PipkinTokens.Spacing.s8,
      ),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    WorkspaceTile(
      letter = remember(name) { tileLetter(name) },
      tint = tileColor(cwd, colors),
      working = status.activity != WorkspaceActivity.Idle,
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = name,
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.content,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      // The home folder's name is its path, `~`, so the line would only repeat it.
      StatusLine(status, folder.takeIf { it != name })
    }
    WorkspaceButtons(name, shown, onActions, onNewChat)
  }
}

/**
 * The line under a workspace's name: Waiting for you or Working after a dot while its chats need the user or work,
 * else its [folder], then its tab count. Each part is its own text, so a right-to-left folder name can't pull the
 * count into it; a path keeps its end when cut short, and the count always shows whole.
 */
@Composable
private fun StatusLine(status: WorkspaceStatus, folder: String?) {
  val colors = pipkinColors
  val tabs = if (status.tabs >
    0
  ) {
    pluralStringResource(R.plurals.shell_workspace_tabs, status.tabs, status.tabs)
  } else {
    null
  }
  val lead = when (status.activity) {
    WorkspaceActivity.Waiting -> stringResource(R.string.shell_workspace_waiting) to colors.dotWarning
    WorkspaceActivity.Working -> stringResource(R.string.shell_workspace_working) to colors.accent
    WorkspaceActivity.Idle -> null
  }
  if (lead == null && folder == null && tabs == null) return
  Row(verticalAlignment = Alignment.CenterVertically) {
    when {
      lead != null -> {
        StatusDot(lead.second)
        Text(
          text = lead.first,
          modifier = Modifier.weight(1f, fill = false).padding(start = 5.dp),
          color = lead.second,
          fontSize = SUBTITLE_SIZE,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      folder != null -> Text(
        text = folder,
        modifier = Modifier.weight(1f, fill = false),
        style = pathTextStyle,
        color = colors.foregroundMuted,
        fontSize = SUBTITLE_SIZE,
        maxLines = 1,
        overflow = TextOverflow.StartEllipsis,
      )
    }
    if (tabs != null) {
      val separator = stringResource(R.string.separator_dot)
      Text(
        text = if (lead != null || folder != null) separator + tabs else tabs,
        color = colors.foregroundMuted,
        fontSize = SUBTITLE_SIZE,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}

/** A workspace row's actions and New chat; New chat sits on a disc in the row the pane shows. */
@Composable
private fun WorkspaceButtons(name: String, shown: Boolean, onActions: () -> Unit, onNewChat: () -> Unit) {
  val colors = pipkinColors
  RoundIconButton(
    icon = R.drawable.ic_lucide_ellipsis,
    contentDescription = stringResource(R.string.archive_workspace_actions, name),
    onClick = onActions,
    modifier = Modifier.size(BUTTON_SIZE),
    tint = colors.foregroundMuted,
    iconSize = PipkinTokens.IconSize.lg,
  )
  RoundIconButton(
    icon = R.drawable.ic_lucide_plus,
    contentDescription = stringResource(R.string.shell_new_chat_in_project, name),
    onClick = onNewChat,
    modifier = Modifier.size(BUTTON_SIZE),
    background = if (shown) colors.surface2 else Color.Transparent,
    tint = if (shown) colors.foreground else colors.foregroundMuted,
    iconSize = PipkinTokens.IconSize.lg,
  )
}

/** The last row of the workspace list: a dashed outline around a plus tile, opening the add-workspace sheet. */
@Composable
internal fun AddWorkspaceRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = PipkinTokens.Spacing.s8)
      .clip(ROW_SHAPE)
      .drawBehind {
        val stroke = 1.dp.toPx()
        val dash = 5.dp.toPx()
        drawRoundRect(
          color = colors.surface3,
          topLeft = Offset(stroke / 2, stroke / 2),
          size = Size(size.width - stroke, size.height - stroke),
          cornerRadius = CornerRadius(ROW_RADIUS.toPx()),
          style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
        )
      }
      .clickable(onClick = onClick)
      .heightIn(min = 56.dp)
      .padding(horizontal = 10.dp, vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    Box(
      modifier = Modifier.size(TILE_SIZE).clip(TILE_SHAPE).background(colors.surface2),
      contentAlignment = Alignment.Center,
    ) {
      LucideIcon(icon = R.drawable.ic_lucide_plus, contentDescription = null, tint = colors.foregroundMuted)
    }
    Text(
      text = stringResource(R.string.shell_add_workspace),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.content,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

/**
 * The workspace's letter on a wash of its colour, or a simmering pot while Pi works there; the name beside it says
 * the same, so TalkBack skips it.
 */
@Composable
private fun WorkspaceTile(letter: String, tint: Color, working: Boolean) {
  val colors = pipkinColors
  Box(
    modifier = Modifier
      .size(TILE_SIZE)
      .clip(TILE_SHAPE)
      .background(if (working) colors.accentSoft else tint.copy(alpha = TILE_WASH_ALPHA))
      .clearAndSetSemantics {},
    contentAlignment = Alignment.Center,
  ) {
    if (working) {
      SimmeringPot(size = PipkinTokens.IconSize.lg, tint = colors.accent)
    } else {
      Text(text = letter, color = tint, fontSize = TILE_LETTER_SIZE, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
  }
}

/**
 * The tile's letter: the first character of [name] as the reader sees it, so a CJK character, an emoji or a letter
 * with accents stays whole, capitalized; a leading dot or underscore, as in `.config`, is skipped. The home folder,
 * named `~`, keeps its tilde.
 */
internal fun tileLetter(name: String): String {
  val trimmed = name.trimStart('.', '_', '-', ' ').ifEmpty { name }
  val characters = BreakIterator.getCharacterInstance()
  characters.setText(trimmed)
  val end = characters.next().takeIf { it != BreakIterator.DONE } ?: trimmed.length
  return trimmed.substring(0, end).uppercase()
}

/** A colour picked by the workspace's folder, so each workspace keeps its colour across launches. */
private fun tileColor(cwd: String, colors: PipkinColors): Color {
  val tints = listOf(colors.accent, colors.dotSuccess, colors.dotWarning, colors.extension, colors.info)
  return tints[Math.floorMod(cwd.hashCode(), tints.size)]
}

private val ROW_RADIUS = 18.dp
private val ROW_SHAPE = RoundedCornerShape(ROW_RADIUS)
private val TILE_SIZE = 36.dp
private val TILE_SHAPE = RoundedCornerShape(12.dp)
private val BUTTON_SIZE = 32.dp
private val TILE_LETTER_SIZE = 16.sp
private val SUBTITLE_SIZE = 12.5.sp
private const val TILE_WASH_ALPHA = 0.14f
private const val SHOWN_BORDER_ALPHA = 0.55f
