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
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.ChatLocation
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.terminal.TerminalSession
import io.jeph.pipkin.ui.chat.ChatScreen
import io.jeph.pipkin.ui.chat.ChatViews
import io.jeph.pipkin.ui.components.LocalPotsSimmer
import io.jeph.pipkin.ui.components.SnackbarStack
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.files.ExplorerPanel
import io.jeph.pipkin.ui.files.FileScreen
import io.jeph.pipkin.ui.git.CommitScreen
import io.jeph.pipkin.ui.sidebar.Sidebar
import io.jeph.pipkin.ui.terminal.TerminalScreen
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.WorkspaceTabsSheet
import io.jeph.pipkin.ui.workspace.splitScreenAvailable
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.ShownTabs
import io.jeph.pipkin.workspace.WorkspaceTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private enum class Panel { Sidebar, Chat, Explorer }

/** Paseo's panel slide: 220ms, standard easing. */
private val PanelAnimation = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

/**
 * Each pane's sliding panels: its sidebar, its screen and its explorer, which slide within that pane's width; and which
 * side each pane is on. Main thread only.
 */
@Stable
private class PanePanels(
  private val primary: AnchoredDraggableState<Panel>,
  private val secondary: AnchoredDraggableState<Panel>,
  private val swappedState: MutableState<Boolean>,
  private val scope: CoroutineScope,
) {
  operator fun get(pane: Pane): AnchoredDraggableState<Panel> = if (pane == Pane.Primary) primary else secondary

  /** Whether a pane shows its sidebar or explorer, which then takes Back before any pane's tab does. */
  val sideOpen: Boolean get() = Pane.entries.any { get(it).targetValue != Panel.Chat }

  /** Whether the second pane is on the main pane's side, the start, and the main pane after it. */
  val swapped: Boolean get() = swappedState.value

  /**
   * Swaps the panes' sides, bringing the pane beside [pane] over to its side on its screen, so what was asked for there
   * shows rather than a side panel left open over it.
   */
  fun swapFrom(pane: Pane) {
    swappedState.value = !swappedState.value
    show(pane.other, Panel.Chat)
  }

  fun show(pane: Pane, panel: Panel) {
    val state = get(pane)
    if (state.targetValue != panel) scope.launch { state.animateTo(panel, PanelAnimation) }
  }

  /** Shows [panel] in [pane], or the pane's screen when [panel] already shows. */
  fun toggle(pane: Pane, panel: Panel) = show(pane, if (get(pane).targetValue == panel) Panel.Chat else panel)

  /**
   * Puts the second pane back on its screen at once and after the main pane, as when split screen closes, so it opens
   * there whichever panel it showed and side it was on.
   */
  fun resetSecondary() {
    swappedState.value = false
    if (secondary.currentValue != Panel.Chat) scope.launch { secondary.snapTo(Panel.Chat) }
  }
}

@Composable
private fun rememberPanePanels(): PanePanels {
  val primary = rememberSaveable(saver = AnchoredDraggableState.Saver()) { AnchoredDraggableState(Panel.Chat) }
  val secondary = rememberSaveable(saver = AnchoredDraggableState.Saver()) { AnchoredDraggableState(Panel.Chat) }
  val swapped = rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  return remember(primary, secondary, swapped, scope) { PanePanels(primary, secondary, swapped, scope) }
}

/**
 * The main screen: the workspace's panes, each with its own sidebar and explorer sliding within it. Opens a chat on
 * entry when none is shown, and shows [requestedChat] in the main pane when a notification asks for it (an archived
 * one opens as its transcript instead); one-shot shell notices show as snackbars. A window too narrow for split screen,
 * such as a folded phone's, closes it.
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
  val splitAvailable = splitScreenAvailable()
  LaunchedEffect(splitAvailable) { if (!splitAvailable) actions.closeSplit() }
  val panels = rememberPanePanels()
  RequestedChatEffect(actions, requestedChat, onOpenArchivedChat, onShown = {
    for (pane in Pane.entries) panels.show(pane, Panel.Chat)
  })

  Box(modifier = Modifier.fillMaxSize()) {
    WorkspacePanes(
      actions = actions,
      splitAvailable = splitAvailable,
      panels = panels,
      snackbar = snackbar,
      onOpenSettings = onOpenSettings,
      sidebar = { pane, onShownInSplit ->
        Sidebar(
          pane = pane,
          actions = actions,
          onShownInSplit = onShownInSplit,
          onClose = { panels.show(pane, Panel.Chat) },
          onOpenSettings = onOpenSettings,
          onOpenArchive = onOpenArchive,
          onOpenOnboarding = onOpenOnboarding,
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

/**
 * Shows [requestedChat] in the main pane as a notification asks for it, then [onShown]; an archived one opens as its
 * transcript through [onOpenArchivedChat] instead.
 */
@Composable
private fun RequestedChatEffect(
  actions: ShellActions,
  requestedChat: MutableStateFlow<ChatLocation?>,
  onOpenArchivedChat: (path: String) -> Unit,
  onShown: () -> Unit,
) {
  val sessionArchive = LocalContext.current.appGraph.sessionArchive
  LaunchedEffect(requestedChat) {
    requestedChat.filterNotNull().collect { chat ->
      requestedChat.value = null
      when {
        sessionArchive.isArchived(chat.sessionPath) -> onOpenArchivedChat(chat.sessionPath)
        actions.openChat(Pane.Primary, chat.cwd, chat.sessionPath) -> onShown()
      }
    }
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
 * Three layers as wide as the pane that slide together: the sidebar at offset 0, the pane's screen at its width and
 * the file explorer at twice it. A horizontal drag anywhere in the pane moves between neighbours; children with their
 * own horizontal scroll see the drag first and consume it.
 */
@Composable
private fun MobilePanels(
  state: AnchoredDraggableState<Panel>,
  sidebar: @Composable () -> Unit,
  workspace: @Composable () -> Unit,
  explorer: @Composable () -> Unit,
  modifier: Modifier = Modifier,
) {
  val fling = AnchoredDraggableDefaults.flingBehavior(state, animationSpec = PanelAnimation)
  Box(
    modifier = modifier
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
 * The main pane and, in split screen, the second pane beside it, each half as wide, each with its own [sidebar] and
 * explorer. Only the main pane opens chats in split screen, and the second pane slides back to its chat when it does.
 * The main pane keeps its place in the composition as split screen opens and closes, so its chat's draft survives that.
 * Showing what the other pane shows swaps the panes' sides instead, which brings it to the side it was asked for on and
 * leaves both panes' chats, drafts and tabs as they were.
 */
@Composable
private fun WorkspacePanes(
  actions: ShellActions,
  splitAvailable: Boolean,
  panels: PanePanels,
  snackbar: SnackbarStackState,
  onOpenSettings: () -> Unit,
  sidebar: @Composable (pane: Pane, onShownInSplit: (() -> Unit)?) -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val panes by graph.registry.shown.collectAsStateWithLifecycle()
  val shown by graph.shownTabs.shown.collectAsStateWithLifecycle()
  // Shared by both panes, so a chat that moves to the other pane keeps its scroll position and parsed replies.
  val views = remember { ChatViews() }
  LaunchedEffect(views, graph.registry) { views.warmHidden(graph.registry) }
  // Not shown for the frame before a narrowed window closes split screen.
  val split = splitAvailable && panes[Pane.Secondary] != null
  LaunchedEffect(split) { if (!split) panels.resetSecondary() }
  val shownPanes = if (split) Pane.entries else listOf(Pane.Primary)
  ClearFocusWhenTerminalLeaves(
    shownPanes.mapNotNullTo(HashSet()) { (ShownTabs.shownIn(shown, panes, it) as? WorkspaceTab.Terminal)?.session },
  )
  PaneRow(panels.swapped) {
    for (pane in shownPanes) {
      if (pane == Pane.Secondary) VerticalDivider(thickness = 1.dp, color = pipkinColors.border)
      // Keyed, so each pane keeps its own state whichever panes show.
      key(pane) {
        val chat = panes[pane]
        val panel = panels[pane].targetValue
        // The tab menu belongs to the pane rather than either of its screens, so it stays open when archiving from it
        // replaces the shown chat and with it the tab shown in its place; it shows the shell's snackbar over itself.
        var tabsOpen by rememberSaveable { mutableStateOf(false) }
        val controls = PaneControls(
          pane = pane,
          onToggleSidebar = { panels.toggle(pane, Panel.Sidebar) },
          onOpenTabs = { tabsOpen = true },
          onToggleExplorer = { panels.toggle(pane, Panel.Explorer) },
          onCloseSplit = if (pane == Pane.Secondary) actions::closeSplit else null,
        )
        val onShownInSplit = {
          panels.show(Pane.Secondary, Panel.Chat)
        }.takeIf { pane == Pane.Primary && splitAvailable }
        val swapPanes = { panels.swapFrom(pane) }
        BackHandler(enabled = panel != Panel.Chat) { panels.show(pane, Panel.Chat) }
        SidePanelOpenedEffect(panel)
        MobilePanels(
          state = panels[pane],
          sidebar = {
            // The sidebar stays composed behind the pane, where its working chats' pots would redraw for nothing.
            CompositionLocalProvider(LocalPotsSimmer provides (panel == Panel.Sidebar)) {
              sidebar(pane, onShownInSplit)
            }
          },
          workspace = {
            val tab = ShownTabs.shownIn(shown, panes, pane)
            WorkspacePane(chat, tab, actions, views, controls, panels.sideOpen, onOpenSettings)
          },
          explorer = {
            PaneExplorer(pane, panel == Panel.Explorer, snackbar, swapPanes) { panels.show(pane, Panel.Chat) }
          },
        )
        if (tabsOpen && chat != null) {
          WorkspaceTabsSheet(pane, chat, actions, snackbar, onShownInSplit, swapPanes, onDismiss = { tabsOpen = false })
        }
      }
    }
  }
}

/**
 * Removing a focused terminal view hands focus to the first text field, a chat's composer, raising the keyboard; so
 * focus is cleared whenever a terminal leaves a pane.
 */
@Composable
private fun ClearFocusWhenTerminalLeaves(terminals: Set<TerminalSession>) {
  val focus = LocalFocusManager.current
  var previous by remember { mutableStateOf(emptySet<TerminalSession>()) }
  LaunchedEffect(terminals) {
    if (!terminals.containsAll(previous)) focus.clearFocus()
    previous = terminals
  }
}

/**
 * One pane's screen: its [chat], or a terminal, file or commit of its workspace shown in its place as [tab], whose
 * header's tab menu switches between the tabs in that workspace. Back from one of those returns to the chat, unless a
 * side panel is open and takes it.
 */
@Composable
private fun WorkspacePane(
  chat: AgentProcess?,
  tab: WorkspaceTab?,
  actions: ShellActions,
  views: ChatViews,
  controls: PaneControls,
  sidePanelOpen: Boolean,
  onOpenSettings: () -> Unit,
) {
  val graph = LocalContext.current.appGraph
  Box(modifier = Modifier.fillMaxSize()) {
    // The chat stays composed under its other tabs, so its draft and scroll position survive a visit to them.
    ChatScreen(
      process = chat,
      actions = actions,
      views = views,
      controls = controls,
      onOpenSettings = onOpenSettings,
      modifier = if (tab != null) Modifier.clearAndSetSemantics {} else Modifier,
    )
    // Always composed, so handlers register in pane order and Back closes the second pane's tab before the main pane's.
    BackHandler(enabled = chat != null && tab != null && !sidePanelOpen) {
      chat?.let { graph.shownTabs.show(controls.pane, it, null) }
    }
    if (chat != null && tab != null) {
      // A pointer input makes the tab a hit target, so touches between its controls don't reach the chat.
      val hitTarget = Modifier.pointerInput(Unit) {}
      when (tab) {
        is WorkspaceTab.Terminal -> TerminalScreen(tab.session, chat, controls, hitTarget)
        is WorkspaceTab.File -> FileScreen(tab.file, chat, controls, hitTarget)
        is WorkspaceTab.Commit -> CommitScreen(tab.commit, chat, controls, hitTarget)
      }
    }
  }
}

/**
 * [pane]'s explorer, for the folder of the chat it shows. Opening a file or a commit shows it in place of that chat and
 * slides back to it, as Paseo's compact explorer does; the shown file's row is highlighted. One the other pane shows
 * stays there, and [onSwapPanes] brings that pane to this side.
 */
@Composable
private fun PaneExplorer(
  pane: Pane,
  open: Boolean,
  snackbar: SnackbarStackState,
  onSwapPanes: () -> Unit,
  onClose: () -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val panes by graph.registry.shown.collectAsStateWithLifecycle()
  val shown by graph.shownTabs.shown.collectAsStateWithLifecycle()
  val chat = panes[pane]
  val shownFile = (ShownTabs.shownIn(shown, panes, pane) as? WorkspaceTab.File)?.file
  val closeOrSwap = { opened: Boolean ->
    if (!opened) onSwapPanes()
    onClose()
  }
  ExplorerPanel(
    cwd = chat?.cwd,
    open = open,
    selectedPath = shownFile?.path,
    snackbar = snackbar,
    onClose = onClose,
    onOpenFile = { path -> chat?.let { closeOrSwap(graph.files.open(pane, it, path)) } },
    onOpenCommit = { commit -> chat?.let { closeOrSwap(graph.commits.open(pane, it, commit)) } },
  )
}
