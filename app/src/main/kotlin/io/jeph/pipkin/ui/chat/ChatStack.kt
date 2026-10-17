package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** The height the stream keeps however much the dialog, the steering box and the composer would take. */
private val MIN_STREAM_HEIGHT = 96.dp

/**
 * The stream over the dialog card, the steering box and the composer, with the height handed out from the bottom up:
 * the composer first, with the keyboard and the widget tabs, then the steering box, then the dialog card, which scrolls
 * inside, and the stream last. All but the stream share what's left above [MIN_STREAM_HEIGHT], so a long dialog and
 * steering queue with the keyboard up still leave the chat and the whole composer on screen. The dialog card takes at
 * most half the height, so the stream keeps the other half while there's room for both, but always gets its min
 * intrinsic height, the least it can be answered in, even from the stream's share, while the room allows. Asking for
 * that height means [dialog] must answer intrinsic queries, so it can't hold a lazy list or a BoxWithConstraints.
 */
@Composable
internal fun ChatStack(
  stream: @Composable () -> Unit,
  dialog: @Composable () -> Unit,
  steering: @Composable () -> Unit,
  composer: @Composable () -> Unit,
  modifier: Modifier = Modifier,
) {
  Layout(contents = listOf(stream, dialog, steering, composer), modifier = modifier.fillMaxWidth()) {
      slots,
      constraints,
    ->
    val width = constraints.maxWidth
    var left = (constraints.maxHeight - MIN_STREAM_HEIGHT.roundToPx()).coerceAtLeast(0)
    val measure = { parts: List<Measurable>, max: Int ->
      parts.map { it.measure(Constraints(maxWidth = width, maxHeight = max)) }.also { placed ->
        left = (left - placed.sumOf { it.height }).coerceAtLeast(0)
      }
    }
    val composerPlaced = measure(slots[3], left)
    val steeringPlaced = measure(slots[2], left)
    val belowDialog = (composerPlaced + steeringPlaced).sumOf { it.height }
    val dialogFloor = slots[1].sumOf { it.minIntrinsicHeight(width) }
    val dialogMax = maxOf(
      minOf(left, constraints.maxHeight / 2),
      minOf(dialogFloor, (constraints.maxHeight - belowDialog).coerceAtLeast(0)),
    )
    val dialogPlaced = measure(slots[1], dialogMax)
    val below = listOf(dialogPlaced, steeringPlaced, composerPlaced).flatten()
    val streamHeight = (constraints.maxHeight - below.sumOf { it.height }).coerceAtLeast(0)
    val streamPlaced = slots[0].map { it.measure(Constraints.fixed(width, streamHeight)) }
    layout(width, constraints.maxHeight) {
      streamPlaced.forEach { it.place(0, 0) }
      var y = streamHeight
      for (placeable in below) {
        placeable.place(0, y)
        y += placeable.height
      }
    }
  }
}
