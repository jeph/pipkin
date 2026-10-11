package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Runs [onTap] for a tap nothing inside handled: buttons consume the release, and scrolls, panel drags
 * and text selection consume the gesture, so those keep their own behavior.
 */
internal fun Modifier.onUnhandledTap(onTap: () -> Unit): Modifier = pointerInput(onTap) {
  awaitEachGesture { if (awaitUnhandledTap()) onTap() }
}

/**
 * Reads one gesture in the Final pass, after the children: true when it ends in a release nobody consumed
 * and the finger stayed within touch slop. Each event is read exactly once; Foundation's
 * `waitForUpOrCancellation(Final)` reads a second Final event after every move to check consumption,
 * which swallows the release that follows a small move and loses the tap.
 */
private suspend fun AwaitPointerEventScope.awaitUnhandledTap(): Boolean {
  val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
  val slop = viewConfiguration.touchSlop
  while (true) {
    val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: return false
    if (change.isConsumed || (change.position - down.position).getDistance() > slop) return false
    if (!change.pressed) return true
  }
}
