package io.jeph.pipkin.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.jeph.pipkin.ui.components.SnackbarStack
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.subagents.ChatSubagentScreen
import io.jeph.pipkin.ui.subagents.SubagentsSheet
import io.jeph.pipkin.ui.subagents.rememberChatSubagents
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.CloseSplitButton
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.PublishTabActions
import io.jeph.pipkin.ui.workspace.ShownTabActions
import io.jeph.pipkin.ui.workspace.SidebarButton
import io.jeph.pipkin.ui.workspace.TabAction
import kotlinx.coroutines.launch

/**
 * One chat in a pane: header, stream and composer for [process]; a null process shows a starting placeholder. What
 * the screen keeps of each chat comes from [views]. The header's buttons, and `/resume`, act through [controls]: the
 * tab menu, the explorer, the sidebar and, in the second pane, closing split screen. A subagent the chat started opens
 * in its place, in the same pane, until Back.
 */
@Composable
internal fun ChatScreen(
  process: AgentProcess?,
  actions: ShellActions,
  views: ChatViews,
  controls: PaneControls,
  onOpenSettings: () -> Unit,
  onOpenProviders: () -> Unit,
  onOpenOnboarding: () -> Unit,
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
    // The pane's own: a subagent shows in place of its chat and never in the other pane or as a tab of its own.
    var subagent by rememberSaveable { mutableStateOf<String?>(null) }
    val shownSubagent = subagent
    if (shownSubagent != null) {
      BackHandler { subagent = null }
      ChatSubagentScreen(
        controller = controller,
        toolCallId = shownSubagent,
        chatTitle = chatTitle(process),
        controls = controls,
        onBack = { subagent = null },
        modifier = modifier,
      )
    } else {
      ChatContent(
        controller,
        snackbar,
        controls,
        onOpenProviders,
        onOpenOnboarding,
        onSheet = { sheet = it },
        onOpenSubagent = { subagent = it },
        modifier,
      )
    }
    PublishChatActions(
      controller,
      controls.shownTab,
      onSheet = { sheet = it },
      onNewChat = { actions.newChat(controls.pane, it) },
      onNewTerminal = { context.appGraph.terminals.open(controls.pane, process.cwd) },
      onArchive = actions::archive,
    )
    ChatSheets(controller, sheet, onSheet = { sheet = it }, onOpenSubagent = { subagent = it })
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

/** The chat's header, stream, question card and composer; a subagent's tool row or the Subagents list opens it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatContent(
  controller: ChatController,
  snackbar: SnackbarStackState,
  controls: PaneControls,
  onOpenProviders: () -> Unit,
  onOpenOnboarding: () -> Unit,
  onSheet: (ChatSheet) -> Unit,
  onOpenSubagent: (toolCallId: String) -> Unit,
  modifier: Modifier,
) {
  val process = controller.process
  val timeline by process.screenTimeline.collectAsStateWithLifecycle()
  val working by process.working.collectAsStateWithLifecycle()
  val subagents = rememberChatSubagents(process, timeline)
  val streamSubagents = remember(subagents, onOpenSubagent) { StreamSubagents(subagents, onOpenSubagent) }
  val subagentsTab = subagents.takeIf { it.runs.isNotEmpty() }?.let {
    SubagentsTab(it.active, it.waiting, onClick = { onSheet(ChatSheet.Subagents) })
  }
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
    ChatHeader(process, controls, Modifier.onUnhandledTap(dismissKeyboard))
    // The keyboard and a dialog both take the room the steering box and widget panels would use, so then the steering
    // box keeps to one line and a panel to a few.
    val tight = WindowInsets.isImeVisible || timeline.pendingUi != null
    ChatStack(
      modifier = Modifier.weight(1f),
      stream = {
        StreamArea(
          controller,
          timeline,
          streamSubagents,
          snackbar,
          onOpenProviders,
          onOpenOnboarding,
          onSheet,
          dismissKeyboard,
        )
      },
      dialog = { PendingDialogCard(controller, timeline) },
      steering = { PendingSteeringBox(controller, timeline, compact = tight) },
      composer = {
        Composer(
          controller = controller,
          model = timeline.model,
          thinkingLevel = timeline.thinkingLevel,
          working = working,
          tight = tight,
          onSheet = onSheet,
          subagents = subagentsTab,
        )
      },
    )
  }
}

/**
 * The stream, with the snackbars over its bottom (or its top while a dialog or steering waits) and the slash menu;
 * [subagents] is what its subagent rows show and open.
 */
@Composable
private fun StreamArea(
  controller: ChatController,
  timeline: TimelineState,
  subagents: StreamSubagents,
  snackbar: SnackbarStackState,
  onOpenProviders: () -> Unit,
  onOpenOnboarding: () -> Unit,
  onSheet: (ChatSheet) -> Unit,
  dismissKeyboard: () -> Unit,
) {
  val process = controller.process
  val processState by process.state.collectAsStateWithLifecycle()
  val backgroundTasks by process.backgroundTasks.collectAsStateWithLifecycle()
  Box(modifier = Modifier.fillMaxSize().onUnhandledTap(dismissKeyboard)) {
    ChatStream(
      timeline = timeline,
      processState = processState,
      view = controller.view,
      onOpenTool = { onSheet(ChatSheet.Tool(it)) },
      onOpenProviders = onOpenProviders,
      onOpenOnboarding = onOpenOnboarding,
      onRestart = process::restart,
      backgroundTasks = backgroundTasks,
      subagents = subagents,
    )
    // A dialog card or the steering box fills the space over the composer, so snackbars move up under the header.
    val raised = timeline.pendingUi != null || timeline.queue.isNotEmpty()
    SnackbarStack(
      snackbar,
      Modifier.align(if (raised) Alignment.TopCenter else Alignment.BottomCenter).padding(PipkinTokens.Spacing.s16),
    )
    SlashMenuHost(controller, ready = processState == AgentProcessState.Ready, Modifier.align(Alignment.BottomCenter))
  }
}

/** The first extension dialog Pi waits on, between the stream and the composer. */
@Composable
internal fun PendingDialogCard(controller: ChatController, timeline: TimelineState) {
  val request = timeline.pendingUi ?: return
  ExtensionUiCard(
    request = request,
    number = timeline.pendingUiNumber,
    count = timeline.pendingUiCount,
    onAnswer = { controller.respondUi(request.id, it) },
    modifier = Modifier.padding(bottom = PipkinTokens.Spacing.s8),
  )
}

/** The slash menu while the draft starts with `/`, 6dp above the composer and 10dp in from the edges, like its tray. */
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
      .padding(start = 10.dp, end = 10.dp, bottom = PipkinTokens.Spacing.s6)
      .widthIn(max = PipkinTokens.contentMaxWidth)
      .fillMaxWidth(),
  )
}

/** Shown while the shell has no process for the pane yet. */
@Composable
private fun PlaceholderChat(controls: PaneControls, modifier: Modifier) {
  Column(modifier = modifier.fillMaxSize().background(pipkinColors.surface0)) {
    HeaderBar(
      title = stringResource(R.string.chat_new_chat),
      leading = { SidebarButton(controls.onToggleSidebar) },
      trailing = { CloseSplitButton(controls) },
    )
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      StatusBanner(AgentProcessState.Starting, onRetry = {}, onOpenOnboarding = {})
    }
  }
}

/**
 * What the chat can do, for the session sheet's actions: start a chat or a terminal in its folder, rename
 * it, reload its agent, copy its session file's path, and archive it. Reloading or archiving asks first while Pi works
 * or runs background tasks; copying and archiving wait for the session file.
 */
@Composable
private fun PublishChatActions(
  controller: ChatController,
  shownTab: ShownTabActions,
  onSheet: (ChatSheet?) -> Unit,
  onNewChat: (cwd: String) -> Unit,
  onNewTerminal: () -> Unit,
  onArchive: (AgentProcess) -> Unit,
) {
  val process = controller.process
  // Reloading or archiving ends Pi, and with it a run or background tasks.
  val holdsPi by process.holdsPi.collectAsStateWithLifecycle()
  val sessionFile by process.sessionFile.collectAsStateWithLifecycle()
  val copy = rememberCopyText()
  val file = sessionFile
  val actions = listOf(
    TabAction(R.string.chat_new_chat, R.drawable.ic_lucide_square_pen, { onNewChat(process.cwd) }),
    TabAction(R.string.terminal_new, R.drawable.ic_lucide_square_terminal, onNewTerminal),
    TabAction(R.string.chat_action_rename, R.drawable.ic_lucide_pencil, { onSheet(ChatSheet.Rename) }),
    TabAction(
      R.string.chat_action_reload,
      R.drawable.ic_lucide_refresh_cw,
      { if (holdsPi) onSheet(ChatSheet.ConfirmReload) else controller.reload() },
    ),
    TabAction(R.string.chat_action_copy_path, R.drawable.ic_lucide_copy, file?.let { { copy(it) } }),
    TabAction(
      R.string.chat_action_archive,
      R.drawable.ic_lucide_archive,
      file?.let { { if (holdsPi) onSheet(ChatSheet.ConfirmArchive) else onArchive(process) } },
      destructive = true,
    ),
  )
  PublishTabActions(shownTab, actions)
}

@Composable
private fun ChatSheets(
  controller: ChatController,
  sheet: ChatSheet?,
  onSheet: (ChatSheet?) -> Unit,
  onOpenSubagent: (toolCallId: String) -> Unit,
) {
  val process = controller.process
  val timeline by process.screenTimeline.collectAsStateWithLifecycle()
  val sessionName by process.sessionName.collectAsStateWithLifecycle()
  val dismiss = { onSheet(null) }
  when (sheet) {
    is ChatSheet.Model -> ModelSheet(
      controller,
      timeline.model,
      timeline.thinkingLevel,
      sheet.query,
      onDismiss = dismiss,
    )

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

    ChatSheet.Subagents -> SubagentsSheet(
      subagents = rememberChatSubagents(process, timeline),
      question = timeline.pendingUi?.title,
      onOpen = {
        dismiss()
        onOpenSubagent(it)
      },
      onStopAll = { controller.scope.launch { process.stopAllSubagents() } },
      onDismiss = dismiss,
    )

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
      icon = R.drawable.ic_lucide_refresh_cw,
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
      icon = R.drawable.ic_lucide_archive,
    )

    else -> Unit
  }
}
