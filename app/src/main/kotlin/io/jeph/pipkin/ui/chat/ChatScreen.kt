package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.TimelineState
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.SnackbarStack
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.files.ExplorerToggle
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.CloseSplitButton
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.TabCountButton
import io.jeph.pipkin.ui.workspace.rememberWorkspaceTabs

/**
 * One chat in a pane: header, stream and composer for [process]; a null process shows a starting placeholder. What
 * the screen keeps of each chat comes from [views]. The header's buttons, and `/resume`, act through [controls]: the
 * tab menu, the explorer, the sidebar and, in the second pane, closing split screen.
 */
@Composable
internal fun ChatScreen(
  process: AgentProcess?,
  actions: ShellActions,
  views: ChatViews,
  controls: PaneControls,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Outlive a process swap, so a message about the closed chat (delete failed) still shows.
  val snackbar = remember { SnackbarStackState() }
  val scope = rememberCoroutineScope()
  if (process == null) {
    PlaceholderChat(controls, modifier)
    return
  }
  // Everything below is per chat. Collecting a chat's flows outside this key would show the previous chat's
  // timeline for a frame after a switch, until the collector of the new one has run.
  key(process) {
    val context = LocalContext.current
    var sheet by remember { mutableStateOf<ChatSheet?>(null) }
    val navigator = rememberNavigator(
      onSheet = { sheet = it },
      onNewChat = { actions.newChat(controls.pane, process.cwd) },
      onOpenTabs = controls.onOpenTabs,
      onOpenSettings = onOpenSettings,
    )
    val controller = remember {
      ChatController(process, views[process], context.applicationContext, scope, snackbar, navigator)
    }
    LaunchedEffect(controller) { controller.collectEffects() }
    LaunchedEffect(controller) { controller.steering.collectQueue() }
    ChatContent(
      controller,
      snackbar,
      controls,
      onOpenSettings,
      onSheet = { sheet = it },
      modifier,
    )
    ChatSheets(
      controller,
      sheet,
      onSheet = { sheet = it },
      onNewChat = { actions.newChat(controls.pane, it) },
      onNewTerminal = { context.appGraph.terminals.open(controls.pane, process.cwd) },
      onArchive = actions::archive,
    )
    ChatDialogs(controller, sheet, onSheet = { sheet = it }, onArchive = actions::archive)
  }
}

/** A [ChatNavigator] that outlives recompositions and always calls the latest callbacks. */
@Composable
private fun rememberNavigator(
  onSheet: (ChatSheet) -> Unit,
  onNewChat: () -> Unit,
  onOpenTabs: () -> Unit,
  onOpenSettings: () -> Unit,
): ChatNavigator {
  val latestSheet by rememberUpdatedState(onSheet)
  val latestNewChat by rememberUpdatedState(onNewChat)
  val latestTabs by rememberUpdatedState(onOpenTabs)
  val latestSettings by rememberUpdatedState(onOpenSettings)
  return remember {
    object : ChatNavigator {
      override fun openSheet(sheet: ChatSheet) = latestSheet(sheet)

      override fun newChat() = latestNewChat()

      override fun openTabs() = latestTabs()

      override fun openSettings() = latestSettings()
    }
  }
}

@Composable
private fun ChatContent(
  controller: ChatController,
  snackbar: SnackbarStackState,
  controls: PaneControls,
  onOpenSettings: () -> Unit,
  onSheet: (ChatSheet) -> Unit,
  modifier: Modifier,
) {
  val process = controller.process
  val timeline by process.screenTimeline.collectAsStateWithLifecycle()
  val busy by process.busy.collectAsStateWithLifecycle()
  val keyboard = LocalSoftwareKeyboardController.current
  val focus = LocalFocusManager.current
  val dismissKeyboard = remember(keyboard, focus) {
    {
      keyboard?.hide()
      focus.clearFocus()
    }
  }
  Column(
    modifier = modifier.fillMaxSize().background(pipkinColors.surface0).closeContextDetailsOnPress(controller),
  ) {
    ChatHeader(
      process,
      controls,
      onActions = { onSheet(ChatSheet.Actions) },
      Modifier.onUnhandledTap(dismissKeyboard),
    )
    val dialogPending = timeline.pendingUi != null
    ChatStack(
      modifier = Modifier.weight(1f),
      stream = { StreamArea(controller, timeline, snackbar, onOpenSettings, onSheet, dismissKeyboard) },
      dialog = { PendingDialogCard(controller, timeline) },
      steering = { PendingSteeringBox(controller, timeline, compact = dialogPending) },
      composer = {
        Composer(
          controller = controller,
          model = timeline.model,
          thinkingLevel = timeline.thinkingLevel,
          busy = busy,
          dialogPending = dialogPending,
          onSheet = onSheet,
        )
      },
    )
  }
}

/** The stream, with the snackbars and the slash menu over its bottom. */
@Composable
private fun StreamArea(
  controller: ChatController,
  timeline: TimelineState,
  snackbar: SnackbarStackState,
  onOpenSettings: () -> Unit,
  onSheet: (ChatSheet) -> Unit,
  dismissKeyboard: () -> Unit,
) {
  val process = controller.process
  val processState by process.state.collectAsStateWithLifecycle()
  Box(modifier = Modifier.fillMaxSize().onUnhandledTap(dismissKeyboard)) {
    ChatStream(
      timeline = timeline,
      processState = processState,
      view = controller.view,
      onOpenTool = { onSheet(ChatSheet.Tool(it)) },
      onOpenSettings = onOpenSettings,
      onRestart = process::restart,
    )
    SnackbarStack(snackbar, Modifier.align(Alignment.BottomCenter).padding(PipkinTokens.Spacing.s16))
    SlashMenuHost(controller, ready = processState == AgentProcessState.Ready, Modifier.align(Alignment.BottomCenter))
  }
}

/** The first extension dialog Pi waits on, between the stream and the composer. */
@Composable
private fun PendingDialogCard(controller: ChatController, timeline: TimelineState) {
  val request = timeline.pendingUi ?: return
  ExtensionUiCard(
    request = request,
    number = timeline.pendingUiNumber,
    count = timeline.pendingUiCount,
    onAnswer = { controller.respondUi(request.id, it) },
    modifier = Modifier.padding(bottom = PipkinTokens.Spacing.s8),
  )
}

/** The slash menu while the draft starts with `/`, 12dp above the composer and as wide as it. */
@Composable
private fun SlashMenuHost(controller: ChatController, ready: Boolean, modifier: Modifier) {
  val query = controller.slash.query
  val keyboard = LocalSoftwareKeyboardController.current
  val focus = LocalFocusManager.current
  // Pi's commands are read each time the menu opens, and again once a starting Pi is ready.
  LaunchedEffect(query != null, ready) { if (query != null && ready) controller.slash.refresh() }
  if (query == null) return
  SlashCommandMenu(
    menu = controller.slash,
    query = query,
    onRan = {
      keyboard?.hide()
      focus.clearFocus()
    },
    modifier = modifier
      .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s16, bottom = PipkinTokens.Spacing.s12)
      .widthIn(max = PipkinTokens.contentMaxWidth)
      .fillMaxWidth(),
  )
}

/**
 * Session name (or the first message) over the project path, with `~` for the Termux home, or over the statuses
 * extensions set while there are any; the tab button with the number of tabs in the workspace sits before the actions
 * button, and the file explorer's toggle ends the row, before the second pane's button that closes split screen.
 */
@Composable
private fun ChatHeader(process: AgentProcess, controls: PaneControls, onActions: () -> Unit, modifier: Modifier) {
  val graph = LocalContext.current.appGraph
  val sessionName by process.sessionName.collectAsStateWithLifecycle()
  val extensionTitle by process.extensionTitle.collectAsStateWithLifecycle()
  val sessionFile by process.sessionFile.collectAsStateWithLifecycle()
  val sessions by graph.sessionRepository.sessions.collectAsStateWithLifecycle()
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val tabs = rememberWorkspaceTabs(controls.pane, process.cwd)
  val firstMessage = sessions.firstOrNull { it.path == sessionFile }?.firstMessage?.takeIf { it.isNotBlank() }
  val statuses by process.extensionStatuses.collectAsStateWithLifecycle()
  val colors = pipkinColors
  val status = remember(statuses, colors) { extensionStatusLine(statuses, colors) }
  HeaderBar(
    title = extensionTitle ?: sessionName ?: firstMessage ?: stringResource(R.string.chat_new_chat),
    subtitle = hello?.home?.let { tildePath(process.cwd, it) } ?: process.cwd,
    status = status,
    modifier = modifier,
    leading = { MenuButton(controls.onToggleSidebar) },
    trailing = {
      TabCountButton(count = tabs.count, onClick = controls.onOpenTabs)
      IconButton28(
        icon = R.drawable.ic_lucide_ellipsis,
        contentDescription = stringResource(R.string.chat_actions),
        onClick = onActions,
      )
      ExplorerToggle(controls.onToggleExplorer)
      CloseSplitButton(controls)
    },
  )
}

@Composable
internal fun MenuButton(onToggleSidebar: () -> Unit) {
  IconButton28(
    icon = R.drawable.ic_lucide_menu,
    contentDescription = stringResource(R.string.chat_toggle_sidebar),
    onClick = onToggleSidebar,
  )
}

/** Shown while the shell has no process for the pane yet. */
@Composable
private fun PlaceholderChat(controls: PaneControls, modifier: Modifier) {
  Column(modifier = modifier.fillMaxSize().background(pipkinColors.surface0)) {
    HeaderBar(
      title = stringResource(R.string.chat_new_chat),
      leading = { MenuButton(controls.onToggleSidebar) },
      trailing = { CloseSplitButton(controls) },
    )
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      StatusBanner(AgentProcessState.Starting, onRetry = {})
    }
  }
}

@Composable
private fun ChatSheets(
  controller: ChatController,
  sheet: ChatSheet?,
  onSheet: (ChatSheet?) -> Unit,
  onNewChat: (cwd: String) -> Unit,
  onNewTerminal: () -> Unit,
  onArchive: (AgentProcess) -> Unit,
) {
  val process = controller.process
  val timeline by process.screenTimeline.collectAsStateWithLifecycle()
  val busy by process.busy.collectAsStateWithLifecycle()
  val sessionFile by process.sessionFile.collectAsStateWithLifecycle()
  val sessionName by process.sessionName.collectAsStateWithLifecycle()
  val copy = rememberCopyText()
  val dismiss = { onSheet(null) }
  when (sheet) {
    ChatSheet.Actions -> ChatActionsSheet(
      actions = ChatActions(
        onNewChat = { onNewChat(process.cwd) },
        onNewTerminal = onNewTerminal,
        onRename = { onSheet(ChatSheet.Rename) },
        onReload = { if (busy) onSheet(ChatSheet.ConfirmReload) else controller.reload() },
        onCopyPath = sessionFile?.let { path -> { copy(path) } },
        onArchive = sessionFile?.let {
          { if (busy) onSheet(ChatSheet.ConfirmArchive) else onArchive(process) }
        },
      ),
      onDismiss = dismiss,
    )

    is ChatSheet.Model -> ModelSheet(controller, timeline.model, sheet.query, onDismiss = dismiss)

    ChatSheet.Thinking -> ThinkingSheet(controller, timeline.model, timeline.thinkingLevel, onDismiss = dismiss)

    is ChatSheet.Tool -> {
      val item = timeline.items.firstOrNull { it.key == sheet.key }
      if (item != null) ToolCallSheet(item, process.timeline::streamingArguments, onDismiss = dismiss)
    }

    is ChatSheet.SessionInfo -> SessionInfoSheet(sheet.stats, sessionName, controller.home, onDismiss = dismiss)

    is ChatSheet.Fork -> ForkSheet(sheet.messages, onDismiss = dismiss) { controller.builtins.fork(it) }

    is ChatSheet.Widget -> {
      val widgets by process.extensionWidgets.collectAsStateWithLifecycle()
      val widget = widgets[sheet.key]
      if (widget != null) WidgetSheet(KeyedWidget(sheet.key, widget), controller.view.widgetTabs, onDismiss = dismiss)
    }

    else -> Unit
  }
}

@Composable
private fun ChatDialogs(
  controller: ChatController,
  sheet: ChatSheet?,
  onSheet: (ChatSheet?) -> Unit,
  onArchive: (AgentProcess) -> Unit,
) {
  val process = controller.process
  val sessionName by process.sessionName.collectAsStateWithLifecycle()
  val dismiss = { onSheet(null) }
  when (sheet) {
    ChatSheet.Rename -> TextInputDialog(
      title = stringResource(R.string.chat_rename_title),
      initial = sessionName.orEmpty(),
      placeholder = stringResource(R.string.chat_rename_hint),
      confirmLabel = stringResource(R.string.chat_save),
      multiline = false,
      onConfirm = {
        dismiss()
        controller.rename(it)
      },
      onDismiss = dismiss,
    )

    ChatSheet.ConfirmReload -> ConfirmDialog(
      title = stringResource(R.string.chat_reload_confirm_title),
      text = stringResource(R.string.chat_reload_confirm_text),
      confirmLabel = stringResource(R.string.chat_reload_confirm),
      onConfirm = {
        dismiss()
        controller.reload()
      },
      onDismiss = dismiss,
    )

    ChatSheet.ConfirmArchive -> ConfirmDialog(
      title = stringResource(R.string.archive_stop_title),
      text = stringResource(R.string.archive_stop_text),
      confirmLabel = stringResource(R.string.archive_stop_confirm),
      onConfirm = {
        dismiss()
        onArchive(process)
      },
      onDismiss = dismiss,
      destructive = true,
    )

    else -> Unit
  }
}
