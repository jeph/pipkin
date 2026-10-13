package io.jeph.pipkin.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Snackbars that show together instead of one after another: each one [show]s for 4 seconds, or until it is swiped
 * away or its action is taken, however many others are showing. Main thread only.
 */
@Stable
internal class SnackbarStackState {
  internal val entries = mutableStateListOf<SnackbarEntry>()

  /** The host's, so a longer timeout the user set in Android's accessibility settings applies. */
  internal var accessibility: AccessibilityManager? = null

  /** How many [SnackbarStackOverlay]s are composed; while any is, they show the snackbars instead of [SnackbarStack]. */
  internal var overlays by mutableIntStateOf(0)

  /** Shows [message], with [actionLabel] as its action when given; true when the action was taken. */
  suspend fun show(message: String, actionLabel: String? = null): Boolean {
    val entry = SnackbarEntry(message, actionLabel)
    entries += entry
    val millis = accessibility?.calculateRecommendedTimeoutMillis(
      originalTimeoutMillis = SHOW_MILLIS,
      containsIcons = false,
      containsText = true,
      containsControls = actionLabel != null,
    ) ?: SHOW_MILLIS
    return try {
      withTimeoutOrNull(millis) { entry.outcome.await() } ?: false
    } finally {
      // The stack drops the entry once it has animated out.
      entry.visibility.targetState = false
    }
  }

  private companion object {
    const val SHOW_MILLIS = 4_000L
  }
}

/** One snackbar in a [SnackbarStackState]; [outcome] is true once its action is taken, false once dismissed. */
internal class SnackbarEntry(val message: String, val actionLabel: String?) {
  val outcome = CompletableDeferred<Boolean>()
  val visibility = MutableTransitionState(false).apply { targetState = true }

  fun performAction() {
    outcome.complete(true)
  }

  fun dismiss() {
    outcome.complete(false)
  }
}

/**
 * [state]'s snackbars in Pipkin's colors, newest at the bottom, with any action in the accent color. Swiping one
 * sideways dismisses it without its action, so an Undo it offers is not taken. Shows nothing while a
 * [SnackbarStackOverlay] of [state] is composed.
 */
@Composable
internal fun SnackbarStack(state: SnackbarStackState, modifier: Modifier = Modifier) {
  if (state.overlays == 0) StackColumn(state, modifier)
}

/**
 * [state]'s snackbars inside a sheet that covers the [SnackbarStack] showing them, so an Undo stays in reach while the
 * sheet is open. Once the sheet closes they show in the [SnackbarStack] again, where they are.
 */
@Composable
internal fun SnackbarStackOverlay(state: SnackbarStackState, modifier: Modifier = Modifier) {
  DisposableEffect(state) {
    state.overlays += 1
    onDispose { state.overlays -= 1 }
  }
  // Waits for its own count, so the stack and an overlay switch in one frame instead of both animating an entry.
  if (state.overlays > 0) StackColumn(state, modifier)
}

@Composable
private fun StackColumn(state: SnackbarStackState, modifier: Modifier) {
  val accessibility = LocalAccessibilityManager.current
  SideEffect { state.accessibility = accessibility }
  Column(modifier = modifier.padding(bottom = MATERIAL_INSET)) {
    for (entry in state.entries) {
      key(entry) { StackedSnackbar(state, entry) }
    }
  }
}

@Composable
private fun StackedSnackbar(state: SnackbarStackState, entry: SnackbarEntry) {
  val colors = pipkinColors
  LaunchedEffect(entry) {
    snapshotFlow { entry.visibility.isIdle && !entry.visibility.currentState }.first { it }
    state.entries.remove(entry)
  }
  AnimatedVisibility(
    visibleState = entry.visibility,
    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
  ) {
    SwipeToDismissBox(
      state = rememberSwipeToDismissBoxState(),
      backgroundContent = {},
      modifier = Modifier
        .padding(start = MATERIAL_INSET, end = MATERIAL_INSET, top = PipkinTokens.Spacing.s8)
        .semantics {
          liveRegion = LiveRegionMode.Polite
          // TalkBack users cannot swipe it away, so they get Material's dismiss action instead.
          dismiss {
            entry.dismiss()
            true
          }
        },
      onDismiss = { entry.dismiss() },
    ) {
      Snackbar(
        action = entry.actionLabel?.let { label ->
          {
            TextButton(
              onClick = entry::performAction,
              colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
            ) { Text(label) }
          }
        },
        shape = RoundedCornerShape(PipkinTokens.Radius.lg),
        containerColor = colors.surface3,
        contentColor = colors.foreground,
      ) { Text(entry.message) }
    }
  }
}

/** The inset Material gives a snackbar shown from its data, kept so the stack sits where single snackbars did. */
private val MATERIAL_INSET = 12.dp
