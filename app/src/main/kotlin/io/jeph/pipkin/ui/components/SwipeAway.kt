package io.jeph.pipkin.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Share of the row's width past which letting go removes it. */
private const val RELEASE_THRESHOLD = 0.35f

/** A rightward fling at least this fast removes the row from any distance. */
private val FLING_VELOCITY = 900.dp

private const val SLIDE_OUT_MILLIS = 160

/**
 * An inbox-style swipe to remove a row, such as archiving a chat or closing a file. Dragging [content] right reveals
 * [mark], which lights up, with a haptic tick, once letting go would remove the row: past [RELEASE_THRESHOLD] of the
 * width or on a fast fling. Then [onSwiped] asks the caller to remove it, which it answers by setting [leaving]; a
 * shorter drag springs back.
 *
 * While [leaving] the content slides out to the end and [onLeft] runs once it is gone; when [leaving] turns false
 * again (a cancelled confirmation) it slides back. With [swipeEnabled] false it only slides on [leaving], as a
 * workspace's row in the sidebar does when the workspace is removed.
 *
 * Only a drag that starts rightward is claimed, so a leftward one still reaches the panels behind.
 */
@Composable
internal fun SwipeAway(
  leaving: Boolean,
  onSwiped: () -> Unit,
  onLeft: () -> Unit,
  containerColor: Color,
  modifier: Modifier = Modifier,
  @DrawableRes mark: Int = R.drawable.ic_lucide_archive,
  swipeEnabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val swipe = remember { SwipeOffset() }
  var width by remember { mutableIntStateOf(0) }
  val latestOnSwiped by rememberUpdatedState(onSwiped)
  val latestOnLeft by rememberUpdatedState(onLeft)
  LaunchedEffect(leaving, width) {
    if (width == 0) return@LaunchedEffect
    if (leaving) {
      swipe.offset.animateTo(width.toFloat(), tween(SLIDE_OUT_MILLIS, easing = FastOutLinearInEasing))
      latestOnLeft()
    } else if (!swipe.dragging) {
      swipe.springBack()
    }
  }
  val offset = swipe.offset.value
  val haptics = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val flingVelocity = with(LocalDensity.current) { FLING_VELOCITY.toPx() }
  Box(
    modifier = modifier
      .onSizeChanged { width = it.width }
      .pointerInput(swipeEnabled && !leaving) {
        if (swipeEnabled && !leaving) swipeDrag(swipe, scope, haptics, flingVelocity) { latestOnSwiped() }
      },
  ) {
    if (offset > 0f) SwipeMark(mark, armed = leaving || offset >= width * RELEASE_THRESHOLD, Modifier.matchParentSize())
    Box(
      modifier = Modifier
        .graphicsLayer { translationX = swipe.offset.value }
        .background(if (offset > 0f) containerColor else Color.Transparent),
    ) {
      content()
    }
  }
}

/** How far a row is swiped; [dragging] while a finger holds it. */
private class SwipeOffset {
  val offset = Animatable(0f)
  var dragging by mutableStateOf(false)

  suspend fun springBack() {
    offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
  }
}

/**
 * Follows a drag that starts rightward, ticking as it crosses [RELEASE_THRESHOLD] either way. Letting go past it
 * (unless flung back left), or flinging right from anywhere, calls [onSwiped]; anything else springs back.
 */
private suspend fun PointerInputScope.swipeDrag(
  swipe: SwipeOffset,
  scope: CoroutineScope,
  haptics: HapticFeedback,
  flingVelocity: Float,
  onSwiped: () -> Unit,
) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var overSlop = 0f
    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
      if (over > 0f) {
        change.consume()
        overSlop = over
      }
    } ?: return@awaitEachGesture
    swipe.dragging = true
    val tracker = VelocityTracker()
    tracker.addPointerInputChange(start)
    val width = size.width.toFloat()
    val threshold = width * RELEASE_THRESHOLD
    var position = (swipe.offset.value + overSlop).coerceIn(0f, width)
    var wasArmed = position >= threshold
    scope.launch { swipe.offset.snapTo(position) }
    val ended = horizontalDrag(start.id) { change ->
      tracker.addPointerInputChange(change)
      position = (position + change.positionChange().x).coerceIn(0f, width)
      change.consume()
      scope.launch { swipe.offset.snapTo(position) }
      val armed = position >= threshold
      if (armed != wasArmed) {
        haptics.performHapticFeedback(
          if (armed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentFrequentTick,
        )
        wasArmed = armed
      }
    }
    swipe.dragging = false
    val velocity = tracker.calculateVelocity().x
    val flung = velocity >= flingVelocity
    val released = position >= threshold && velocity > -flingVelocity
    if (ended && position > 0f && (flung || released)) onSwiped() else scope.launch { swipe.springBack() }
  }
}

/** The [icon] the swipe uncovers; it lights up and grows once letting go would remove the row. */
@Composable
private fun SwipeMark(@DrawableRes icon: Int, armed: Boolean, modifier: Modifier) {
  val colors = pipkinColors
  val background by animateColorAsState(if (armed) colors.accent else colors.surface3, label = "swipeMark")
  val scale by animateFloatAsState(
    targetValue = if (armed) 1.2f else 0.9f,
    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    label = "swipeMarkScale",
  )
  Box(
    modifier = modifier.background(background).padding(start = PipkinTokens.Spacing.s24),
    contentAlignment = Alignment.CenterStart,
  ) {
    LucideIcon(
      icon = icon,
      contentDescription = null,
      modifier = Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
      },
      tint = if (armed) colors.accentForeground else colors.foregroundMuted,
    )
  }
}
