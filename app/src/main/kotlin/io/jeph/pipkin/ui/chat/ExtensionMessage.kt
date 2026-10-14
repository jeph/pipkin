package io.jeph.pipkin.ui.chat

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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.MarkdownCache
import io.jeph.pipkin.ui.components.PipkinMarkdown
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.math.roundToInt

/** How many markdown lines a folded extension message shows. */
private const val FOLDED_LINES = 6

/** How many lines past [FOLDED_LINES] a message may run before it folds; paragraph spacing makes real lines taller. */
private const val FOLD_SLACK_LINES = 3

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
      FoldedContent(item.key) {
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
 * [content] cut to [FOLDED_LINES] lines over a fade, with a toggle to show all of it. Content at most [FOLD_SLACK_LINES]
 * lines taller shows whole, without the toggle, since Show more would reveal next to nothing. Unfolding is kept by the
 * row's [key] while the row scrolls away.
 */
@Composable
private fun FoldedContent(key: String, content: @Composable ColumnScope.() -> Unit) {
  val colors = pipkinColors
  val linePx = with(LocalDensity.current) { PipkinTokens.LineHeight.markdown.toPx() }
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
            .background(Brush.verticalGradient(listOf(Color.Transparent, colors.surface1))),
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
