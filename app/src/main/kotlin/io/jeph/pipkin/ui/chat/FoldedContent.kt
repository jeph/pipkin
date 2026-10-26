package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.math.roundToInt

/** How far past its last whole line content may run before it counts as longer, since line heights round. */
private const val LINE_TOLERANCE = 0.5f

/** Where folded content ends uncut, which the fold reads in the pass that cuts it. */
private val UncutBottom = HorizontalAlignmentLine(::maxOf)

/**
 * [content] cut to [lines] lines of [lineHeight] over a fade into [fadeTo] unless [expanded], with a toggle that calls
 * [onToggle]. Content at most [slackLines] lines taller shows whole, without the toggle, since Show more would reveal
 * next to nothing. Whether it folds is decided in the pass that measures it, so the toggle is there from the first
 * frame: a fold that gained it a frame later would grow under a list scrolled up to it and push what's below.
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
  val density = LocalDensity.current
  val linePx = with(density) { lineHeight.toPx() }
  val foldedPx = (linePx * lines).roundToInt()
  val foldsAbovePx = (linePx * (lines + slackLines + LINE_TOLERANCE)).roundToInt()
  val fadePx = with(density) { PipkinTokens.Spacing.s24.roundToPx() }
  Layout(
    contents = listOf(
      {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            // Measured whole and cut here, so folded content never lays out at full height first.
            .layout { measurable, constraints ->
              val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
              val height = if (expanded || placeable.height <= foldsAbovePx) placeable.height else foldedPx
              layout(placeable.width, height, mapOf(UncutBottom to placeable.height)) { placeable.place(0, 0) }
            },
          content = content,
        )
      },
      { Box(modifier = Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, fadeTo)))) },
      {
        Text(
          text = stringResource(if (expanded) R.string.chat_show_less else R.string.chat_show_more),
          modifier = Modifier.clickable(onClick = onToggle).padding(top = PipkinTokens.Spacing.s8),
          color = colors.accent,
          fontSize = PipkinTokens.FontSize.meta,
          fontWeight = FontWeight.Bold,
        )
      },
    ),
  ) { (contentMeasurables, fadeMeasurables, toggleMeasurables), constraints ->
    val shown = contentMeasurables.single().measure(constraints.copy(minHeight = 0))
    val uncut = shown[UncutBottom]
    // The fade and toggle stay unplaced while the content shows whole, so they neither draw nor take touches.
    if (uncut == AlignmentLine.Unspecified || uncut <= foldsAbovePx) {
      return@Layout layout(shown.width, shown.height) { shown.placeRelative(0, 0) }
    }
    val toggle = toggleMeasurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
    val fade = if (expanded) null else fadeMeasurables.single().measure(Constraints.fixed(shown.width, fadePx))
    val width = constraints.constrainWidth(maxOf(shown.width, toggle.width))
    layout(width, constraints.constrainHeight(shown.height + toggle.height)) {
      shown.placeRelative(0, 0)
      fade?.placeRelative(0, shown.height - fadePx)
      toggle.placeRelative(0, shown.height)
    }
  }
}
