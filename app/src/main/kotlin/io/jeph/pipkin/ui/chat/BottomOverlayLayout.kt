package io.jeph.pipkin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp

private enum class OverlaySlot { Overlay, Content }

/**
 * Fills the area with [content] and draws [overlay] over its bottom edge, so content stays visible
 * around the overlay's own shapes instead of stopping above its bounds. [content] gets the
 * overlay's height to pad its scrollable end. The overlay is measured first and content composed
 * after it, so a height change (the keyboard sliding in) reaches content in the same frame.
 */
@Composable
internal fun BottomOverlayLayout(
  overlay: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable (overlayHeight: Dp) -> Unit,
) {
  val padded = remember { PaddedContent() }
  SubcomposeLayout(modifier) { constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val overlays = subcompose(OverlaySlot.Overlay, overlay).map { it.measure(loose) }
    val overlayHeight = overlays.maxOfOrNull { it.height } ?: 0
    val body = subcompose(OverlaySlot.Content, padded.of(content, overlayHeight.toDp())).map { it.measure(constraints) }
    layout(constraints.maxWidth, constraints.maxHeight) {
      body.forEach { it.place(0, 0) }
      overlays.forEach { it.place(0, constraints.maxHeight - it.height) }
    }
  }
}

/** Keeps the same content lambda while its inputs are unchanged, so a remeasure alone recomposes nothing. */
private class PaddedContent {
  private var source: (@Composable (Dp) -> Unit)? = null
  private var height = Dp.Unspecified
  private var bound: @Composable () -> Unit = {}

  fun of(content: @Composable (Dp) -> Unit, overlayHeight: Dp): @Composable () -> Unit {
    if (content !== source || overlayHeight != height) {
      source = content
      height = overlayHeight
      bound = { content(overlayHeight) }
    }
    return bound
  }
}
