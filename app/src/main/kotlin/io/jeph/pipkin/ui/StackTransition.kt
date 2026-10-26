package io.jeph.pipkin.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * How a stack of screens moves from depth [from] to depth [to]: a deeper screen slides in from the end edge over the one
 * below, which shifts a quarter width for depth, and a popped screen slides back out over the one it uncovers. The same
 * depth cross-fades. Same timing as the sidebar panels.
 *
 * AnimatedContent keeps the z-index a screen entered with, so every transition sets it to the screen's stack depth: a
 * deeper screen always draws over a shallower one, however it got there.
 */
internal fun stackTransition(from: Int, to: Int): ContentTransform {
  val slide = tween<IntOffset>(durationMillis = SCREEN_SLIDE_MILLIS, easing = FastOutSlowInEasing)
  val fade = tween<Float>(durationMillis = SCREEN_SLIDE_MILLIS, easing = FastOutSlowInEasing)
  val transform = when {
    to > from -> slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it / 4 }
    to < from -> slideInHorizontally(slide) { -it / 4 } togetherWith slideOutHorizontally(slide) { it }
    else -> fadeIn(fade) togetherWith fadeOut(fade)
  }
  return transform.apply { targetContentZIndex = to.toFloat() }
}

private const val SCREEN_SLIDE_MILLIS = 220
