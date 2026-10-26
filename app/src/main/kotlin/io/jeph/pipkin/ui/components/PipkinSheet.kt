package io.jeph.pipkin.ui.components

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.SoftwareKeyboardControllerCompat
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A modal bottom sheet. With [heightFraction] the sheet is that share of the window tall whatever its
 * content, and [content] can fill the space with weights. With [maxHeightFraction] the sheet wraps its
 * content up to that share; give the scrolling part `weight(1f, fill = false)` so it scrolls there.
 * When the keyboard is up, the sheet waits for it to close and reopens it once the sheet is gone.
 * [content] gets `dismiss`, for an action that closes the sheet: after [TOUCH_HOLD_MILLIS], so the
 * touch shows, it slides the sheet away as a swipe or Back does, runs its `then` once the sheet is out
 * of sight, and calls [onDismissRequest] once it's hidden, also when it leaves the screen on the way.
 * An action that would reshape the sheet or open something over it goes in `then`; one that shows the
 * pick in the sheet, or decides whether it closes, runs first. A `dismiss` while one is under way does
 * nothing, and a finger that catches the sheet as it slides keeps it open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipkinSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  heightFraction: Float? = null,
  maxHeightFraction: Float? = null,
  content: @Composable ColumnScope.(dismiss: (then: () -> Unit) -> Unit) -> Unit,
) {
  if (!keyboardClosedForSheet()) return
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val dismiss = rememberSheetDismiss(sheetState, onDismissRequest)
  val windowHeight = LocalWindowInfo.current.containerSize.height
  val sizing = when {
    heightFraction != null -> Modifier.sheetHeight(heightFraction, windowHeight, fill = true)
    maxHeightFraction != null -> Modifier.sheetHeight(maxHeightFraction, windowHeight, fill = false)
    else -> Modifier
  }
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    sheetState = sheetState,
    shape = SHEET_SHAPE,
    containerColor = pipkinColors.surface1,
    contentColor = pipkinColors.foreground,
    dragHandle = {
      BottomSheetDefaults.DragHandle(
        width = 40.dp,
        height = 4.dp,
        shape = RoundedCornerShape(2.dp),
        color = pipkinColors.surface3,
      )
    },
  ) {
    val guard = remember { SheetGestureGuard() }
    // Drags on the sheet's content that no list takes, as on a header, go through [guard] like a list's, rather
    // than to the drag Material puts on the sheet itself, which only the drag handle above the content keeps.
    val unscrolled = rememberScrollableState { 0f }
    Column(
      modifier = Modifier
        .nestedScroll(guard)
        .scrollable(unscrolled, Orientation.Vertical)
        .pointerInput(guard) {
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            guard.listScrolled = false
          }
        }
        .then(sizing)
        .navigationBarsPadding()
        .padding(bottom = PipkinTokens.Spacing.s16),
    ) {
      content(dismiss)
    }
  }
}

/** The `dismiss` [PipkinSheet] hands its content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberSheetDismiss(sheetState: SheetState, onDismissRequest: () -> Unit): (then: () -> Unit) -> Unit {
  val hideScope = rememberCoroutineScope()
  val currentOnDismissRequest = rememberUpdatedState(onDismissRequest)
  return remember(sheetState, hideScope) { SheetDismiss(sheetState, hideScope, currentOnDismissRequest) }
}

@OptIn(ExperimentalMaterial3Api::class)
private class SheetDismiss(
  private val sheetState: SheetState,
  private val hideScope: CoroutineScope,
  private val onDismissRequest: State<() -> Unit>,
) : (() -> Unit) -> Unit {
  private var closing = false

  override fun invoke(then: () -> Unit) {
    if (closing) return
    closing = true
    var thenRan = false
    val runThen = {
      if (!thenRan) {
        thenRan = true
        then()
      }
    }
    hideScope.launch {
      delay(TOUCH_HOLD_MILLIS)
      // Material's spring settles well after the sheet is out of sight; what the action brings up needn't wait for it.
      launch {
        delay(SLIDE_MILLIS)
        runThen()
      }
      sheetState.hide()
    }.invokeOnCompletion {
      closing = false
      runThen()
      if (!sheetState.isVisible || !hideScope.isActive) onDismissRequest.value()
    }
  }
}

/** How long a sheet stays after an action closes it, so the touch's ripple and what it picked show first. */
private const val TOUCH_HOLD_MILLIS = 120L

/** How long a sheet takes to slide out of sight once it starts to hide. */
private const val SLIDE_MILLIS = 100L

private val SHEET_SHAPE = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

/**
 * Keeps a touch from moving the sheet where it can't go. Material hands a list's leftover scroll and fling to the
 * sheet, so a swipe or fling that reaches the top of a list would carry on and close the sheet: once a list has
 * scrolled during a touch, the rest of that touch and its fling stay with the list, while a touch that starts with
 * the list at its top still drags the sheet. [listScrolled] is reset when a finger goes down. And an upward fling
 * left over once the sheet is open has nowhere to take it, yet Material 1.4 throws the sheet up past its top with
 * that speed and springs it back, so a fast swipe up makes it jump; the guard keeps that fling.
 */
private class SheetGestureGuard : NestedScrollConnection {
  var listScrolled = false

  override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
    if (consumed.y != 0f) listScrolled = true
    return if (listScrolled) Offset(0f, available.y) else Offset.Zero
  }

  override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
    if (listScrolled || available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
}

/**
 * Closes the keyboard for a sheet and returns whether it's down, so the sheet can open. A sheet that opens
 * while the keyboard closes stalls: Material pads the sheet's window by the keyboard, and every frame of
 * that shrinking padding restarts the sheet's slide from rest. Hiding the keyboard just before the sheet
 * opens doesn't help, since the sheet's window replays the whole closing. Once the sheet is gone, the
 * keyboard comes back if the field it was open for still has focus.
 */
@Composable
private fun keyboardClosedForSheet(): Boolean {
  val view = LocalView.current
  val density = LocalDensity.current
  val ime = WindowInsets.ime
  // Read without observing, so the keyboard's closing frames don't recompose the sheet.
  val keyboardWasOpen = remember { Snapshot.withoutReadObservation { ime.getBottom(density) > 0 } }
  var keyboardClosed by remember { mutableStateOf(!keyboardWasOpen) }
  if (!keyboardWasOpen) return true
  LaunchedEffect(Unit) {
    // Hides the window's keyboard whoever opened it; Compose's controller misses the terminal view's.
    SoftwareKeyboardControllerCompat(view).hide()
    snapshotFlow { ime.getBottom(density) }.first { it == 0 }
    keyboardClosed = true
  }
  DisposableEffect(Unit) { onDispose { view.reopenKeyboardOnWindowFocus() } }
  return keyboardClosed
}

/**
 * Shows the keyboard for the focused text field once this window has focus back from the sheet's. Compose
 * fields reopen it themselves when the window regains focus; this covers the terminal's view, which
 * doesn't, and a sheet dismissed before it opened, when focus never left. Nothing shows when the sheet's
 * action moved focus off the field or removed it.
 */
private fun View.reopenKeyboardOnWindowFocus() {
  val reopen = {
    val focused = rootView.findFocus()
    if (focused?.onCheckIsTextEditor() == true) SoftwareKeyboardControllerCompat(focused).show()
  }
  if (hasWindowFocus()) return reopen()
  viewTreeObserver.addOnWindowFocusChangeListener(
    object : ViewTreeObserver.OnWindowFocusChangeListener {
      override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) return
        viewTreeObserver.removeOnWindowFocusChangeListener(this)
        reopen()
      }
    },
  )
}

/**
 * Sizes the sheet's content so the whole sheet is [fraction] of the window, or at most that when not
 * [fill]. Sizing the sheet itself would also shrink the space Material positions it in, pinning it to
 * the top of the screen. The sheet spans the window, so the drag handle and insets above the content
 * take `windowHeight - maxHeight`.
 */
private fun Modifier.sheetHeight(fraction: Float, windowHeight: Int, fill: Boolean): Modifier =
  layout { measurable, constraints ->
    val height = (constraints.maxHeight - windowHeight * (1 - fraction)).roundToInt()
      .coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(
      constraints.copy(minHeight = if (fill) height else constraints.minHeight, maxHeight = height),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
  }
