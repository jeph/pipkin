package io.jeph.pipkin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.ui.AppNavigator
import io.jeph.pipkin.ui.Destination
import io.jeph.pipkin.ui.MainScreen
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.onboarding.OnboardingScreen
import io.jeph.pipkin.ui.settings.ProviderEditorScreen
import io.jeph.pipkin.ui.settings.SettingsScreen
import io.jeph.pipkin.ui.theme.PipkinTheme
import io.jeph.pipkin.ui.theme.pipkinColors

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val graph = appGraph
    setContent {
      val theme by graph.preferences.theme.collectAsStateWithLifecycle()
      PipkinTheme(theme) {
        Surface(modifier = Modifier.fillMaxSize(), color = pipkinColors.surface0) {
          PipkinApp(graph)
        }
      }
    }
  }
}

/**
 * Shows the top of the navigator's stack; onboarding is the root until setup completes. Pushed
 * screens slide in from the end edge over the one below, and slide back out when popped.
 */
@Composable
private fun PipkinApp(graph: AppGraph) {
  val navigator = rememberSaveable(saver = AppNavigator.Saver) {
    AppNavigator(listOf(if (graph.preferences.setupComplete.value) Destination.Main else Destination.Onboarding))
  }
  val actions = remember(graph) { ShellActions(graph) }
  val stack by navigator.stack.collectAsStateWithLifecycle()
  val holder = rememberSaveableStateHolder()
  ForgetPopped(stack, holder)
  BackHandler(enabled = stack.size > 1) { navigator.pop() }
  AnimatedContent(
    targetState = stack,
    transitionSpec = { stackTransition(from = initialState.size, to = targetState.size) },
    contentKey = { AppNavigator.key(it.last()) },
    label = "navigation",
  ) { shown ->
    val top = shown.last()
    holder.SaveableStateProvider(AppNavigator.key(top)) {
      when (top) {
        Destination.Main -> MainScreen(
          actions = actions,
          onOpenSettings = { navigator.push(Destination.Settings) },
          onOpenOnboarding = { navigator.push(Destination.Onboarding) },
        )

        Destination.Settings -> SettingsScreen(
          onBack = { navigator.pop() },
          onOpenOnboarding = { navigator.push(Destination.Onboarding) },
          onEditProvider = { navigator.push(Destination.ProviderEditor(it)) },
        )

        is Destination.ProviderEditor -> ProviderEditorScreen(id = top.id, onBack = { navigator.pop() })

        Destination.Onboarding -> OnboardingScreen(
          onFinished = { navigator.replaceAll(Destination.Main) },
          onAddProvider = { navigator.push(Destination.ProviderEditor(null)) },
        )
      }
    }
  }
}

/**
 * Same timing as the sidebar panels. The screen underneath shifts a quarter width for depth; the
 * popped screen stays on top while it leaves. Replacing the whole stack cross-fades.
 *
 * AnimatedContent keeps the z-index a screen entered with, so every transition sets it to the
 * screen's stack depth: a deeper screen always draws over a shallower one, however it got there.
 */
private fun stackTransition(from: Int, to: Int): ContentTransform {
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

/** Drops the saved UI state of screens that left the stack, so reopening one starts fresh. */
@Composable
private fun ForgetPopped(stack: List<Destination>, holder: SaveableStateHolder) {
  val keys = stack.map(AppNavigator::key)
  val previous = remember { mutableListOf<String>() }
  LaunchedEffect(keys) {
    (previous - keys.toSet()).forEach(holder::removeState)
    previous.clear()
    previous.addAll(keys)
  }
}
