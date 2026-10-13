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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ChatLocation
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.chat.ChatScreen
import io.jeph.pipkin.ui.components.LocalPotsSimmer
import io.jeph.pipkin.ui.components.SnackbarStack
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.sidebar.Sidebar
import io.jeph.pipkin.ui.terminal.TerminalScreen
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.workspace.WorkspaceTabsSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private enum class Panel { Sidebar, Chat }

/** Paseo's panel slide: 220ms, standard easing. */
private val PanelAnimation = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

/**
 * The main screen: the sidebar and the chat as full-width sliding panels. Opens a chat on entry when
 * none is shown, and shows [requestedChat] when a notification asks for it (an archived one opens as its
 * transcript instead); one-shot shell notices show as snackbars.
 */
@Composable
internal fun MainScreen(
  actions: ShellActions,
  requestedChat: MutableStateFlow<ChatLocation?>,
  onOpenSettings: () -> Unit,
  onOpenArchive: () -> Unit,
  onOpenArchivedChat: (path: String) -> Unit,
  onOpenOnboarding: () -> Unit,
) {
  val snackbar = remember { SnackbarStackState() }
  ShellNotices(actions, snackbar)
  LaunchedEffect(actions) { actions.openAtStartup() }

  val panels = rememberSaveable(saver = AnchoredDraggableState.Saver()) { AnchoredDraggableState(Panel.Chat) }
  val scope = rememberCoroutineScope()
  val showPanel: (Panel) -> Unit = { panel -> scope.launch { panels.animateTo(panel, PanelAnimation) } }
  val sidebarOpen = panels.targetValue == Panel.Sidebar
  BackHandler(enabled = sidebarOpen) { showPanel(Panel.Chat) }
  SidebarOpenedEffect(sidebarOpen)
  val sessionArchive = LocalContext.current.appGraph.sessionArchive
  LaunchedEffect(requestedChat) {
    requestedChat.filterNotNull().collect { chat ->
      requestedChat.value = null
      when {
        sessionArchive.isArchived(chat.sessionPath) -> onOpenArchivedChat(chat.sessionPath)
        actions.openChat(chat.cwd, chat.sessionPath) -> showPanel(Panel.Chat)
      }
    }
  }

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
            onOpenArchive = onOpenArchive,
            onOpenOnboarding = onOpenOnboarding,
          )
        }
      },
      workspace = {
        WorkspacePanel(
          actions = actions,
          sidebarOpen = sidebarOpen,
          snackbar = snackbar,
          onToggleSidebar = { showPanel(if (sidebarOpen) Panel.Chat else Panel.Sidebar) },
          onOpenSettings = onOpenSettings,
        )
      },
    )
    SnackbarStack(
      state = snackbar,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(PipkinTokens.Spacing.s16),
    )
  }
}

/** Shows [actions]' notices as they arrive, alongside any already showing; an archive notice offers Undo while it shows. */
@Composable
private fun ShellNotices(actions: ShellActions, snackbar: SnackbarStackState) {
  val resources = LocalResources.current
  val genericError = stringResource(R.string.shell_error_generic)
  val undo = stringResource(R.string.archive_undo)
  LaunchedEffect(actions) {
    actions.notices.collect { notice ->
      launch {
        when (notice) {
          is ShellNotice.Failure -> snackbar.show(notice.message ?: genericError)

          is ShellNotice.Archived -> {
            val count = notice.paths.size
            val message = resources.getQuantityString(R.plurals.archive_archived, count, count)
            if (snackbar.show(message, actionLabel = undo)) actions.unarchive(notice.paths)
          }
        }
      }
    }
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
 * The layer beside the sidebar: the shown chat, or a terminal of its workspace shown in its place, whose header's tab
 * menu switches between the chats and terminals in that workspace. Back from a terminal returns to the chat, unless
 * the sidebar is open and takes it. The tab menu belongs to the panel rather than either screen, so it stays open when
 * archiving from it replaces the shown chat and with it the terminal shown in its place; it shows [snackbar] over
 * itself.
 */
@Composable
private fun WorkspacePanel(
  actions: ShellActions,
  sidebarOpen: Boolean,
  snackbar: SnackbarStackState,
  onToggleSidebar: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val process by graph.registry.visible.collectAsStateWithLifecycle()
  val shown by graph.terminals.shown.collectAsStateWithLifecycle()
  var tabsOpen by rememberSaveable { mutableStateOf(false) }
  val openTabs = { tabsOpen = true }
  val chat = process
  val terminal = shown?.takeIf { it.chat == chat }?.session
  val focus = LocalFocusManager.current
  val terminalShown = terminal != null
  // Removing the focused terminal view hands focus to the first text field, the chat's composer, raising the keyboard.
  LaunchedEffect(terminalShown) { if (!terminalShown) focus.clearFocus() }
  Box(modifier = Modifier.fillMaxSize()) {
    // The chat stays composed under its terminal, so its draft and scroll position survive a visit to the terminal.
    ChatScreen(
      process = chat,
      actions = actions,
      onOpenTabs = openTabs,
      onToggleSidebar = onToggleSidebar,
      onOpenSettings = onOpenSettings,
      modifier = if (terminal != null) Modifier.clearAndSetSemantics {} else Modifier,
    )
    if (chat != null && terminal != null) {
      BackHandler(enabled = !sidebarOpen) { graph.terminals.show(chat, null) }
      TerminalScreen(
        session = terminal,
        chat = chat,
        onToggleSidebar = onToggleSidebar,
        onOpenTabs = openTabs,
        // A pointer input makes the terminal a hit target, so touches between its controls don't reach the chat.
        modifier = Modifier.pointerInput(Unit) {},
      )
    }
  }
  if (tabsOpen && chat != null) WorkspaceTabsSheet(chat, actions, snackbar, onDismiss = { tabsOpen = false })
}
