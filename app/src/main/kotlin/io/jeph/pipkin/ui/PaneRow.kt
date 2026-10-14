package io.jeph.pipkin.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

/**
 * [content]'s panes side by side, each half as wide: the main pane, then in split screen a divider and the second pane,
 * which [swapped] places on the main pane's side. Only placement changes, so a swap keeps every pane's state and views.
 */
@Composable
internal fun PaneRow(swapped: Boolean, content: @Composable () -> Unit) {
  Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val single = measurables.singleOrNull()
    if (single != null) {
      val pane = single.measure(Constraints.fixed(width, height))
      return@Layout layout(width, height) { pane.placeRelative(0, 0) }
    }
    val (main, divider, second) = measurables
    val line = divider.measure(Constraints(maxWidth = width, minHeight = height, maxHeight = height))
    val mainWidth = (width - line.width) / 2
    val mainPane = main.measure(Constraints.fixed(mainWidth, height))
    val secondPane = second.measure(Constraints.fixed(width - line.width - mainWidth, height))
    val (start, end) = if (swapped) secondPane to mainPane else mainPane to secondPane
    layout(width, height) {
      start.placeRelative(0, 0)
      line.placeRelative(start.width, 0)
      end.placeRelative(start.width + line.width, 0)
    }
  }
}
