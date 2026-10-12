package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter

/** How far above the bottom a settled scroll still counts as at the bottom. */
private val FOLLOW_TOLERANCE = 4.dp

/**
 * Pins the end of [state]'s content to the bottom of its viewport while [follow] is on. It reacts to
 * layout, not to the data, because markdown parses and code highlights off the main thread: content
 * grows frames after its text arrives. A drag stops following, and only a user scroll that settles at
 * the bottom resumes it, so a reader above the bottom is never moved. [distanceFromBottom] is in
 * pixels, and [scrollDown] scrolls by such a distance.
 */
@Composable
internal fun AutoFollow(
  state: ScrollableState,
  interactionSource: InteractionSource,
  follow: MutableState<Boolean>,
  distanceFromBottom: () -> Int,
  scrollDown: suspend (distance: Int) -> Unit,
) {
  val tolerance = with(LocalDensity.current) { FOLLOW_TOLERANCE.roundToPx() }
  LaunchedEffect(interactionSource) {
    interactionSource.interactions.collect { if (it is DragInteraction.Start) follow.value = false }
  }
  LaunchedEffect(state, tolerance) {
    // Only turns following on: the pinning's own scrolls also settle, and content that grew
    // meanwhile must not read as the user leaving the bottom.
    snapshotFlow { state.isScrollInProgress }
      .filter { !it }
      .collect { if (!follow.value && distanceFromBottom() <= tolerance) follow.value = true }
  }
  LaunchedEffect(state) {
    snapshotFlow { follow.value to distanceFromBottom() }
      .collect { (following, distance) -> if (following && distance != 0) pin(distance, scrollDown) }
  }
}

/** [AutoFollow] for a scrolling column. */
@Composable
internal fun AutoFollow(state: ScrollState, follow: MutableState<Boolean>) {
  AutoFollow(
    state = state,
    interactionSource = state.interactionSource,
    follow = follow,
    // maxValue is Int.MAX_VALUE until the first layout, when there is nothing to follow yet.
    distanceFromBottom = { if (state.maxValue == Int.MAX_VALUE) 0 else state.maxValue - state.value },
    scrollDown = { state.scrollBy(it.toFloat()) },
  )
}

/**
 * Scrolls [state] to its end while [follow] is on, between the scroll container measuring its content and
 * placing it, so content that grows is never drawn for a frame below where it settles, as [AutoFollow]'s
 * scroll alone would leave it. Goes before `verticalScroll(state)`.
 */
internal fun Modifier.pinEnd(state: ScrollState, follow: State<Boolean>): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  layout(placeable.width, placeable.height) {
    // Read without observing: the scroll position is not an input to this layout step.
    Snapshot.withoutReadObservation {
      val distance = state.maxValue - state.value
      if (follow.value && distance > 0 && !state.isScrollInProgress) state.dispatchRawDelta(distance.toFloat())
    }
    placeable.place(0, 0)
  }
}

/** Scrolls the end into view; a user drag that takes over cancels only this scroll. */
private suspend fun pin(distance: Int, scrollDown: suspend (distance: Int) -> Unit) {
  try {
    scrollDown(distance)
  } catch (_: CancellationException) {
    // A higher-priority scroll owns the list; rethrow only if this effect itself was cancelled.
    currentCoroutineContext().ensureActive()
  }
}
