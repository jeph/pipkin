package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.math.roundToInt

/** How far past its last whole line content may run before it counts as longer, since line heights round. */
private const val LINE_TOLERANCE = 0.5f

/**
 * [content] cut to [lines] lines of [lineHeight] over a fade into [fadeTo] unless [expanded], with a toggle that calls
 * [onToggle]. Content at most [slackLines] lines taller shows whole, without the toggle, since Show more would reveal
 * next to nothing.
 */
@Composable
internal fun FoldedContent(
  lines: Int,
  slackLines: Int,
  lineHeight: TextUnit,
  fadeTo: Color,
  expanded: Boolean,
  onToggle: () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = pipkinColors
  val linePx = with(LocalDensity.current) { lineHeight.toPx() }
  val foldedPx = (linePx * lines).roundToInt()
  val foldsAbovePx = (linePx * (lines + slackLines + LINE_TOLERANCE)).roundToInt()
  var fullHeight by remember { mutableIntStateOf(0) }
  val overflows = fullHeight > foldsAbovePx
  Column {
    Box {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clipToBounds()
          // Measured whole and cut here, in the same pass, so folded content never lays out at full height first.
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
        modifier = Modifier.clickable(onClick = onToggle).padding(top = PipkinTokens.Spacing.s8),
        color = colors.accent,
        fontSize = PipkinTokens.FontSize.meta,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}
