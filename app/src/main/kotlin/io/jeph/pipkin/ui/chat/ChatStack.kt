package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.node.invalidateParentData
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** The height the stream keeps however much the dialog, the steering box and the composer would take. */
private val MIN_STREAM_HEIGHT = 96.dp

/**
 * The stream over the dialog card, the steering box and the composer, with the height handed out from the bottom up:
 * the composer first, with the keyboard and the widget tabs, then the steering box, then the dialog card, which scrolls
 * inside, and the stream last. All but the stream share what's left above [MIN_STREAM_HEIGHT], so a long dialog and
 * steering queue with the keyboard up still leave the chat and the whole composer on screen. The dialog card takes at
 * most half the height, plus what it marks with [dialogExtraHeight], so the stream keeps the other half while there's
 * room for both, but always gets its min intrinsic height, the least it can be answered in, even from the stream's
 * share, while the room allows. Asking for that height means [dialog] must answer intrinsic queries, so it can't hold
 * a lazy list or a BoxWithConstraints.
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
    val dialogMax = dialogMaxHeight(
      height = constraints.maxHeight,
      left = left,
      belowDialog = belowDialog,
      floor = slots[1].sumOf { it.minIntrinsicHeight(width) },
      extra = slots[1].sumOf { (it.parentData as? DialogExtraHeightNode)?.height ?: 0 },
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

/**
 * The most the dialog card may take of [ChatStack]'s [height]: half of it plus the card's [extra], within the [left]
 * room above the stream's minimum, or the card's [floor] when that's more, within what's above [belowDialog].
 */
internal fun dialogMaxHeight(height: Int, left: Int, belowDialog: Int, floor: Int, extra: Int): Int = maxOf(
  minOf(left, height / 2 + extra),
  minOf(floor, (height - belowDialog).coerceAtLeast(0)),
)

/**
 * Marks [height] pixels of the dialog card, the root of [ChatStack]'s dialog, as beyond its half of the height: room
 * for something above the card's own content, such as the pill naming the subagent that asked, which would otherwise
 * come out of the answers.
 */
internal fun Modifier.dialogExtraHeight(height: Int): Modifier = then(DialogExtraHeightElement(height))

private data class DialogExtraHeightElement(val height: Int) : ModifierNodeElement<DialogExtraHeightNode>() {
  override fun create() = DialogExtraHeightNode(height)

  override fun update(node: DialogExtraHeightNode) {
    node.height = height
    node.invalidateParentData()
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "dialogExtraHeight"
    properties["height"] = height
  }
}

private class DialogExtraHeightNode(var height: Int) :
  Modifier.Node(),
  ParentDataModifierNode {
  override fun Density.modifyParentData(parentData: Any?): Any = this@DialogExtraHeightNode
}
