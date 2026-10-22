package io.jeph.pipkin.ui.components

import android.view.View
import android.view.ViewTreeObserver
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.core.view.SoftwareKeyboardControllerCompat
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/**
 * A modal bottom sheet. With [heightFraction] the sheet is that share of the window tall whatever its
 * content, and [content] can fill the space with weights. With [maxHeightFraction] the sheet wraps its
 * content up to that share; give the scrolling part `weight(1f, fill = false)` so it scrolls there.
 * When the keyboard is up, the sheet waits for it to close and reopens it once the sheet is gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipkinSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  heightFraction: Float? = null,
  maxHeightFraction: Float? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  if (!keyboardClosedForSheet()) return
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
    Column(
      modifier = Modifier
        .then(sizing)
        .navigationBarsPadding()
        .padding(bottom = PipkinTokens.Spacing.s16),
      content = content,
    )
  }
}

private val SHEET_SHAPE = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

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
