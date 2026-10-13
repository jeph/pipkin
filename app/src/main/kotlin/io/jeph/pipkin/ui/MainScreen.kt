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
import io.jeph.pipkin.ui.files.FileExplorerPanel
import io.jeph.pipkin.ui.files.FileScreen
import io.jeph.pipkin.ui.sidebar.Sidebar
import io.jeph.pipkin.ui.terminal.TerminalScreen
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.workspace.WorkspaceTabsSheet
import io.jeph.pipkin.workspace.WorkspaceTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private enum class Panel { Sidebar, Chat, Explorer }

/** Paseo's panel slide: 220ms, standard easing. */
private val PanelAnimation = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

/**
 * The main screen: the sidebar, the chat and the file explorer as full-width sliding panels. Opens a chat on entry
 * when none is shown, and shows [requestedChat] when a notification asks for it (an archived one opens as its
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
  val explorerOpen = panels.targetValue == Panel.Explorer
  BackHandler(enabled = sidebarOpen || explorerOpen) { showPanel(Panel.Chat) }
  SidePanelOpenedEffect(panels.targetValue)
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
          sidePanelOpen = sidebarOpen || explorerOpen,
          snackbar = snackbar,
          onToggleSidebar = { showPanel(if (sidebarOpen) Panel.Chat else Panel.Sidebar) },
          onToggleExplorer = { showPanel(if (explorerOpen) Panel.Chat else Panel.Explorer) },
          onOpenSettings = onOpenSettings,
        )
      },
      explorer = {
        ExplorerPanel(open = explorerOpen, snackbar = snackbar, onClose = { showPanel(Panel.Chat) })
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

/**
 * Opening a side panel hides the keyboard, so it doesn't cover the panel; opening the sidebar also refreshes the chat
 * list.
 */
@Composable
private fun SidePanelOpenedEffect(panel: Panel) {
  val keyboard = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  val sessionRepository = LocalContext.current.appGraph.sessionRepository
  LaunchedEffect(panel) {
    if (panel == Panel.Chat) return@LaunchedEffect
    keyboard?.hide()
    focusManager.clearFocus(force = true)
    if (panel == Panel.Sidebar) sessionRepository.refresh()
  }
}

/**
 * Three full-width layers that slide together: the sidebar at offset 0, the workspace at the panel's width and the
 * file explorer at twice it. A horizontal drag anywhere moves between neighbours; children with their own horizontal
 * scroll see the drag first and consume it.
 */
@Composable
private fun MobilePanels(
  state: AnchoredDraggableState<Panel>,
  sidebar: @Composable () -> Unit,
  workspace: @Composable () -> Unit,
  explorer: @Composable () -> Unit,
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
            Panel.Explorer at size.width * 2f
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
    Box(
      modifier = Modifier.fillMaxSize().graphicsLayer {
        translationX = size.width * 2 - state.offsetOr(size.width)
      },
    ) {
      explorer()
    }
  }
}

/** The offset, or the settled value's position before the first layout set the anchors. */
private fun AnchoredDraggableState<Panel>.offsetOr(width: Float): Float = offset.takeUnless { it.isNaN() }
  ?: when (currentValue) {
    Panel.Sidebar -> 0f
    Panel.Chat -> width
    Panel.Explorer -> width * 2
  }

/**
 * The layer beside the sidebar: the shown chat, or a terminal or file of its workspace shown in its place, whose
 * header's tab menu switches between the tabs in that workspace. Back from a terminal or file returns to the chat,
 * unless a side panel is open and takes it. The tab menu belongs to the panel rather than either screen, so it stays
 * open when archiving from it replaces the shown chat and with it the tab shown in its place; it shows [snackbar] over
 * itself.
 */
@Composable
private fun WorkspacePanel(
  actions: ShellActions,
  sidePanelOpen: Boolean,
  snackbar: SnackbarStackState,
  onToggleSidebar: () -> Unit,
  onToggleExplorer: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val process by graph.registry.visible.collectAsStateWithLifecycle()
  val shown by graph.shownTab.shown.collectAsStateWithLifecycle()
  var tabsOpen by rememberSaveable { mutableStateOf(false) }
  val openTabs = { tabsOpen = true }
  val chat = process
  val tab = shown?.takeIf { it.chat == chat }?.tab
  val focus = LocalFocusManager.current
  val terminalShown = tab is WorkspaceTab.Terminal
  // Removing the focused terminal view hands focus to the first text field, the chat's composer, raising the keyboard.
  LaunchedEffect(terminalShown) { if (!terminalShown) focus.clearFocus() }
  Box(modifier = Modifier.fillMaxSize()) {
    // The chat stays composed under its other tabs, so its draft and scroll position survive a visit to them.
    ChatScreen(
      process = chat,
      actions = actions,
      onOpenTabs = openTabs,
      onToggleSidebar = onToggleSidebar,
      onToggleExplorer = onToggleExplorer,
      onOpenSettings = onOpenSettings,
      modifier = if (tab != null) Modifier.clearAndSetSemantics {} else Modifier,
    )
    if (chat != null && tab != null) {
      BackHandler(enabled = !sidePanelOpen) { graph.shownTab.show(chat, null) }
      // A pointer input makes the tab a hit target, so touches between its controls don't reach the chat.
      val hitTarget = Modifier.pointerInput(Unit) {}
      when (tab) {
        is WorkspaceTab.Terminal -> TerminalScreen(
          session = tab.session,
          chat = chat,
          onToggleSidebar = onToggleSidebar,
          onOpenTabs = openTabs,
          onToggleExplorer = onToggleExplorer,
          modifier = hitTarget,
        )

        is WorkspaceTab.File -> FileScreen(
          file = tab.file,
          chat = chat,
          onToggleSidebar = onToggleSidebar,
          onOpenTabs = openTabs,
          onToggleExplorer = onToggleExplorer,
          modifier = hitTarget,
        )
      }
    }
  }
  if (tabsOpen && chat != null) WorkspaceTabsSheet(chat, actions, snackbar, onDismiss = { tabsOpen = false })
}

/**
 * The file explorer beside the workspace, for the shown chat's folder. Opening a file shows it in place of the chat
 * and slides back to it, as Paseo's compact explorer does; the shown file's row is highlighted.
 */
@Composable
private fun ExplorerPanel(open: Boolean, snackbar: SnackbarStackState, onClose: () -> Unit) {
  val graph = LocalContext.current.appGraph
  val chat by graph.registry.visible.collectAsStateWithLifecycle()
  val shown by graph.shownTab.shown.collectAsStateWithLifecycle()
  val shownFile = (shown?.takeIf { it.chat == chat }?.tab as? WorkspaceTab.File)?.file
  FileExplorerPanel(
    cwd = chat?.cwd,
    open = open,
    selectedPath = shownFile?.path,
    snackbar = snackbar,
    onClose = onClose,
    onOpenFile = { path ->
      chat?.let { graph.files.open(it, path) }
      onClose()
    },
  )
}
