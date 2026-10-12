package io.jeph.pipkin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.chat.ChatScreen
import io.jeph.pipkin.ui.components.LocalPotsSimmer
import io.jeph.pipkin.ui.sidebar.Sidebar
import io.jeph.pipkin.ui.theme.PipkinTokens
import kotlinx.coroutines.launch

private enum class Panel { Sidebar, Chat }

/** Paseo's panel slide: 220ms, standard easing. */
private val PanelAnimation = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

/**
 * The main screen: the sidebar and the chat as full-width sliding panels. Opens a chat on entry when
 * none is shown; one-shot shell notices show as a snackbar.
 */
@Composable
internal fun MainScreen(actions: ShellActions, onOpenSettings: () -> Unit, onOpenOnboarding: () -> Unit) {
  val snackbar = remember { SnackbarHostState() }
  val genericError = stringResource(R.string.shell_error_generic)
  LaunchedEffect(actions) { actions.notices.collect { snackbar.showSnackbar(it ?: genericError) } }
  LaunchedEffect(actions) { actions.openAtStartup() }

  val panels = rememberSaveable(saver = AnchoredDraggableState.Saver()) { AnchoredDraggableState(Panel.Chat) }
  val scope = rememberCoroutineScope()
  val showPanel: (Panel) -> Unit = { panel -> scope.launch { panels.animateTo(panel, PanelAnimation) } }
  val sidebarOpen = panels.targetValue == Panel.Sidebar
  BackHandler(enabled = sidebarOpen) { showPanel(Panel.Chat) }
  SidebarOpenedEffect(sidebarOpen)

  Box(modifier = Modifier.fillMaxSize()) {
    MobilePanels(
      state = panels,
      sidebar = {
        // The sidebar stays composed behind the chat, where its working chats' pots would redraw for nothing.
        CompositionLocalProvider(LocalPotsSimmer provides sidebarOpen) {
          Sidebar(
            actions = actions,
            onClose = { showPanel(Panel.Chat) },
            onOpenSettings = onOpenSettings,
            onOpenOnboarding = onOpenOnboarding,
          )
        }
      },
      workspace = {
        WorkspacePanel(
          actions = actions,
          onToggleSidebar = { showPanel(if (sidebarOpen) Panel.Chat else Panel.Sidebar) },
          onOpenSettings = onOpenSettings,
        )
      },
    )
    SnackbarHost(
      hostState = snackbar,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(PipkinTokens.Spacing.s16),
    )
  }
}

/** Opening the sidebar hides the keyboard and refreshes the chat list. */
@Composable
private fun SidebarOpenedEffect(sidebarOpen: Boolean) {
  val keyboard = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  val sessionRepository = LocalContext.current.appGraph.sessionRepository
  LaunchedEffect(sidebarOpen) {
    if (sidebarOpen) {
      keyboard?.hide()
      focusManager.clearFocus(force = true)
      sessionRepository.refresh()
    }
  }
}

/**
 * Two full-width layers that slide together: the sidebar at offset 0 and the workspace at the
 * panel's width. A horizontal drag anywhere moves between them; children with their own horizontal
 * scroll see the drag first and consume it.
 */
@Composable
private fun MobilePanels(
  state: AnchoredDraggableState<Panel>,
  sidebar: @Composable () -> Unit,
  workspace: @Composable () -> Unit,
) {
  val fling = AnchoredDraggableDefaults.flingBehavior(state, animationSpec = PanelAnimation)
  Box(
    modifier = Modifier
      .fillMaxSize()
      .clipToBounds()
      .onSizeChanged { size ->
        state.updateAnchors(
          DraggableAnchors {
            Panel.Sidebar at 0f
            Panel.Chat at size.width.toFloat()
          },
          // Keep the shown panel when the width changes in place (multi-window, foldables).
          newTarget = state.targetValue,
        )
      }
      // Dragging right (towards the sidebar) lowers the offset.
      .anchoredDraggable(state, reverseDirection = true, orientation = Orientation.Horizontal, flingBehavior = fling),
  ) {
    Box(modifier = Modifier.fillMaxSize().graphicsLayer { translationX = -state.offsetOr(size.width) }) {
      sidebar()
    }
    Box(modifier = Modifier.fillMaxSize().graphicsLayer { translationX = size.width - state.offsetOr(size.width) }) {
      workspace()
    }
  }
}

/** The offset, or the settled value's position before the first layout set the anchors. */
private fun AnchoredDraggableState<Panel>.offsetOr(width: Float): Float =
  offset.takeUnless { it.isNaN() } ?: if (currentValue == Panel.Chat) width else 0f

/**
 * The layer beside the sidebar. It holds only the chat; Paseo's Changes, Files and Terminal tabs
 * become a tab switcher here without changing [MobilePanels].
 */
@Composable
private fun WorkspacePanel(actions: ShellActions, onToggleSidebar: () -> Unit, onOpenSettings: () -> Unit) {
  val process by LocalContext.current.appGraph.registry.visible.collectAsStateWithLifecycle()
  ChatScreen(
    process = process,
    onToggleSidebar = onToggleSidebar,
    onNewChat = { cwd -> actions.newChat(cwd) },
    onOpenSettings = onOpenSettings,
    onChatDeleted = actions::openMostRecent,
  )
}
