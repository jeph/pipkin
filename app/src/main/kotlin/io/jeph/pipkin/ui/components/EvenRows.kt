package io.jeph.pipkin.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Lays [content] out in rows of children of one width, [gap] apart. A row holds as many as fit the widest child at its
 * own width, and the children are spread evenly over the fewest rows that takes, so six that need two rows go three
 * and three rather than five and one.
 */
@Composable
internal fun EvenRows(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Layout(content = content, modifier = modifier) { measurables, constraints ->
    if (measurables.isEmpty()) return@Layout layout(constraints.minWidth, 0) {}
    val width = constraints.maxWidth
    val spacing = gap.roundToPx()
    val widest = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
    val fit = ((width + spacing) / (widest + spacing)).coerceIn(1, measurables.size)
    val rows = (measurables.size + fit - 1) / fit
    val perRow = (measurables.size + rows - 1) / rows
    val cell = (width - spacing * (perRow - 1)) / perRow
    val placeables = measurables.map { it.measure(Constraints.fixedWidth(cell)) }
    val rowHeights = placeables.chunked(perRow).map { row -> row.maxOf { it.height } }
    layout(width, rowHeights.sum() + spacing * (rows - 1)) {
      var y = 0
      placeables.chunked(perRow).forEachIndexed { index, row ->
        row.forEachIndexed { column, placeable -> placeable.placeRelative(column * (cell + spacing), y) }
        y += rowHeights[index] + spacing
      }
    }
  }
}
