package io.jeph.pipkin.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fades the top of a list into [color] once it's scrolled and the bottom while rows lie below. Painted in the
 * background's colour over the rows rather than as a transparency mask, which would draw the whole list offscreen;
 * [state] is read while drawing, so scrolling redraws the fades without recomposing the list.
 */
internal fun Modifier.scrollFades(state: LazyListState, color: Color): Modifier = drawWithContent {
  drawContent()
  val fade = SCROLL_FADE.toPx().coerceAtMost(size.height / 2)
  if (state.canScrollBackward) {
    drawRect(Brush.verticalGradient(listOf(color, Color.Transparent), endY = fade), size = Size(size.width, fade))
  }
  if (state.canScrollForward) {
    val top = size.height - fade
    drawRect(
      Brush.verticalGradient(listOf(Color.Transparent, color), startY = top, endY = size.height),
      topLeft = Offset(0f, top),
      size = Size(size.width, fade),
    )
  }
}

private val SCROLL_FADE = 40.dp
