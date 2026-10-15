package io.jeph.pipkin.ui.chat

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.NoticeLevel
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.MarkdownCache
import io.jeph.pipkin.ui.components.PipkinMarkdown
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.math.roundToInt

/** How many lines a folded extension message or notice shows. */
private const val FOLDED_LINES = 6

/** How many lines past [FOLDED_LINES] content may run before it folds; paragraph spacing makes real lines taller. */
private const val FOLD_SLACK_LINES = 3

/** Paseo's notice line height, which also sets its icon's 20dp row. */
private val NOTICE_LINE_HEIGHT = 20.sp

/** Paseo's notice tints, the same in both themes: blue-300, amber-500 and red-500, behind the text at 10%. */
private val INFO_TINT = Color(0xFF93C5FD)
private val WARNING_TINT = Color(0xFFF59E0B)
private val ERROR_WASH = Color(0xFFEF4444)
private const val WASH_ALPHA = 0.1f

/**
 * A message an extension posted, as Pi's terminal shows it: the extension's message type over its content, set apart
 * from the conversation. Long text folds behind Show more; images always show whole.
 */
@Composable
internal fun ExtensionMessage(
  item: TimelineItem.ExtensionMessage,
  markdownCache: MarkdownCache,
  modifier: Modifier = Modifier,
) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.xl)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = PipkinTokens.Spacing.s8)
      .background(colors.surface1, shape)
      .border(1.dp, colors.borderAccent, shape)
      .padding(PipkinTokens.Spacing.s12),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
    ) {
      LucideIcon(
        icon = R.drawable.ic_lucide_plug,
        contentDescription = stringResource(R.string.chat_extension_message),
        size = PipkinTokens.IconSize.sm,
        tint = colors.foregroundMuted,
      )
      Text(
        text = item.customType,
        color = colors.foregroundMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = PipkinTokens.FontSize.sm,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (item.markdown.isNotBlank()) {
      FoldedContent(item.key, PipkinTokens.LineHeight.markdown, fadeTo = colors.surface1) {
        PipkinMarkdown(markdown = item.markdown, modifier = Modifier.fillMaxWidth(), cache = markdownCache)
      }
    }
    if (item.images.isNotEmpty()) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
        verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
      ) {
        item.images.forEach { ImageThumbnail(it) }
      }
    }
  }
}

/**
 * A notice an extension showed with `ctx.ui.notify`, as Paseo shows one: a card tinted by its level. Long notices fold
 * like extension messages, and the text can be selected.
 */
@Composable
internal fun ExtensionNoticeCard(item: TimelineItem.ExtensionNotice, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  val look = when (item.level) {
    NoticeLevel.Info -> NoticeLook(R.drawable.ic_lucide_info, R.string.chat_notice_info, INFO_TINT, INFO_TINT)

    NoticeLevel.Warning ->
      NoticeLook(R.drawable.ic_lucide_triangle_alert, R.string.chat_notice_warning, WARNING_TINT, WARNING_TINT)

    NoticeLevel.Error ->
      NoticeLook(R.drawable.ic_lucide_circle_x, R.string.chat_notice_error, colors.destructive, ERROR_WASH)
  }
  // Opaque, so a folded notice's fade ends in the card's own colour.
  val background = look.wash.copy(alpha = WASH_ALPHA).compositeOver(colors.surface0)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(background, RoundedCornerShape(PipkinTokens.Radius.md))
      .padding(horizontal = PipkinTokens.Spacing.s12, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    LucideIcon(
      icon = look.icon,
      contentDescription = stringResource(look.description),
      modifier = Modifier.padding(top = PipkinTokens.Spacing.s2),
      tint = look.tint,
    )
    Column(modifier = Modifier.weight(1f)) {
      FoldedContent(item.key, NOTICE_LINE_HEIGHT, fadeTo = background) {
        SelectionContainer {
          Text(
            text = item.message,
            color = colors.foreground,
            fontSize = PipkinTokens.FontSize.base,
            lineHeight = NOTICE_LINE_HEIGHT,
          )
        }
      }
    }
  }
}

private data class NoticeLook(
  @DrawableRes val icon: Int,
  @StringRes val description: Int,
  val tint: Color,
  val wash: Color,
)

/**
 * [content] cut to [FOLDED_LINES] lines of [lineHeight] over a fade into [fadeTo], with a toggle to show all of it.
 * Content at most [FOLD_SLACK_LINES] lines taller shows whole, without the toggle, since Show more would reveal next
 * to nothing. Unfolding is kept by the row's [key] while the row scrolls away.
 */
@Composable
private fun FoldedContent(
  key: String,
  lineHeight: TextUnit,
  fadeTo: Color,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = pipkinColors
  val linePx = with(LocalDensity.current) { lineHeight.toPx() }
  val foldedPx = (linePx * FOLDED_LINES).roundToInt()
  val foldsAbovePx = (linePx * (FOLDED_LINES + FOLD_SLACK_LINES)).roundToInt()
  var expanded by rememberSaveable(key) { mutableStateOf(false) }
  var fullHeight by remember { mutableIntStateOf(0) }
  val overflows = fullHeight > foldsAbovePx
  Column {
    Box {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clipToBounds()
          // Measured whole and cut here, in the same pass, so a folded message never lays out at full height first.
          .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
            val height = if (expanded || placeable.height <= foldsAbovePx) placeable.height else foldedPx
            layout(placeable.width, height) { placeable.place(0, 0) }
          }
          .onSizeChanged { fullHeight = it.height },
        content = content,
      )
      if (overflows && !expanded) {
        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(PipkinTokens.Spacing.s24)
            .background(Brush.verticalGradient(listOf(Color.Transparent, fadeTo))),
        )
      }
    }
    if (overflows) {
      Text(
        text = stringResource(if (expanded) R.string.chat_show_less else R.string.chat_show_more),
        modifier = Modifier.clickable { expanded = !expanded }.padding(top = PipkinTokens.Spacing.s8),
        color = colors.accent,
        fontSize = PipkinTokens.FontSize.meta,
      )
    }
  }
}
