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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.SnackbarStack
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.ChatTabsSheet
import io.jeph.pipkin.ui.workspace.TabCountButton
import io.jeph.pipkin.ui.workspace.rememberWorkspaceChats

/**
 * One chat: header, stream and composer for [process]; a null process shows a starting placeholder. The header's tab
 * button opens the tab menu of the chats in [process]'s workspace.
 */
@Composable
internal fun ChatScreen(
  process: AgentProcess?,
  actions: ShellActions,
  onToggleSidebar: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Outlive a process swap, so a message about the closed chat (delete failed) still shows.
  val snackbar = remember { SnackbarStackState() }
  val scope = rememberCoroutineScope()
  var tabsOpen by rememberSaveable { mutableStateOf(false) }
  if (process == null) {
    PlaceholderChat(onToggleSidebar, modifier)
    return
  }
  val context = LocalContext.current
  val keyboard = LocalSoftwareKeyboardController.current
  val focus = LocalFocusManager.current
  val openTabs = {
    keyboard?.hide()
    focus.clearFocus()
    tabsOpen = true
  }
  var sheet by remember(process) { mutableStateOf<ChatSheet?>(null) }
  val navigator = rememberNavigator(
    onSheet = { sheet = it },
    onNewChat = { actions.newChat(process.cwd) },
    onOpenTabs = openTabs,
    onOpenSettings = onOpenSettings,
  )
  val controller = remember(process) {
    ChatController(process, context.appGraph, context.applicationContext, scope, snackbar, navigator)
  }
  LaunchedEffect(controller) { controller.collectEffects() }
  LaunchedEffect(controller) { controller.steering.collectQueue() }
  ChatContent(controller, snackbar, onToggleSidebar, openTabs, onOpenSettings, onSheet = { sheet = it }, modifier)
  ChatSheets(
    controller,
    sheet,
    onSheet = { sheet = it },
    onNewChat = { actions.newChat(it) },
    onArchive = actions::archive,
  )
  ChatDialogs(controller, sheet, onSheet = { sheet = it }, onArchive = actions::archive)
  if (tabsOpen) ChatTabsSheet(process, actions, onDismiss = { tabsOpen = false })
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
  onToggleSidebar: () -> Unit,
  onOpenTabs: () -> Unit,
  onOpenSettings: () -> Unit,
  onSheet: (ChatSheet) -> Unit,
  modifier: Modifier,
) {
  val process = controller.process
  val timeline by process.screenTimeline.collectAsStateWithLifecycle()
  val processState by process.state.collectAsStateWithLifecycle()
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
      onToggleSidebar,
      onOpenTabs,
      onActions = { onSheet(ChatSheet.Actions) },
      Modifier.onUnhandledTap(dismissKeyboard),
    )
    Box(modifier = Modifier.weight(1f).fillMaxWidth().onUnhandledTap(dismissKeyboard)) {
      // A fresh list per chat, so switching chats never carries the old one's scroll position over.
      key(process) {
        ChatStream(
          timeline = timeline,
          processState = processState,
          follow = controller.follow,
          onOpenTool = { onSheet(ChatSheet.Tool(it)) },
          onOpenSettings = onOpenSettings,
          onRestart = process::restart,
        )
      }
      SnackbarStack(snackbar, Modifier.align(Alignment.BottomCenter).padding(PipkinTokens.Spacing.s16))
      SlashMenuHost(controller, ready = processState == AgentProcessState.Ready, Modifier.align(Alignment.BottomCenter))
    }
    if (timeline.queue.isNotEmpty()) {
      SteeringBox(
        summary = controller.steering.summary(timeline.queue),
        editEnabled = !timeline.queueInFlight,
        sendNowEnabled = !timeline.queueInFlight && !controller.sending,
        onEdit = controller.steering::edit,
        onSendNow = controller.steering::sendNow,
        modifier = Modifier.padding(bottom = PipkinTokens.Spacing.s8),
      )
    }
    Composer(
      controller = controller,
      model = timeline.model,
      thinkingLevel = timeline.thinkingLevel,
      busy = busy,
      onOpenModelSheet = { onSheet(ChatSheet.Model()) },
      onOpenThinkingSheet = { onSheet(ChatSheet.Thinking) },
    )
  }
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
 * Session name (or the first message) over the project path, with `~` for the Termux home; the tab button with the
 * number of chats in the workspace sits before the actions button.
 */
@Composable
private fun ChatHeader(
  process: AgentProcess,
  onToggleSidebar: () -> Unit,
  onOpenTabs: () -> Unit,
  onActions: () -> Unit,
  modifier: Modifier,
) {
  val graph = LocalContext.current.appGraph
  val sessionName by process.sessionName.collectAsStateWithLifecycle()
  val sessionFile by process.sessionFile.collectAsStateWithLifecycle()
  val sessions by graph.sessionRepository.sessions.collectAsStateWithLifecycle()
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val tabs = rememberWorkspaceChats(process)
  val firstMessage = sessions.firstOrNull { it.path == sessionFile }?.firstMessage?.takeIf { it.isNotBlank() }
  HeaderBar(
    title = sessionName ?: firstMessage ?: stringResource(R.string.chat_new_chat),
    subtitle = hello?.home?.let { tildePath(process.cwd, it) } ?: process.cwd,
    modifier = modifier,
    leading = { MenuButton(onToggleSidebar) },
    trailing = {
      TabCountButton(count = tabs.count, onClick = onOpenTabs)
      IconButton28(
        icon = R.drawable.ic_lucide_ellipsis,
        contentDescription = stringResource(R.string.chat_actions),
        onClick = onActions,
      )
    },
  )
}

@Composable
private fun MenuButton(onToggleSidebar: () -> Unit) {
  IconButton28(
    icon = R.drawable.ic_lucide_menu,
    contentDescription = stringResource(R.string.chat_toggle_sidebar),
    onClick = onToggleSidebar,
  )
}

/** Shown while the shell has no process for the chat panel yet. */
@Composable
private fun PlaceholderChat(onToggleSidebar: () -> Unit, modifier: Modifier) {
  Column(modifier = modifier.fillMaxSize().background(pipkinColors.surface0)) {
    HeaderBar(title = stringResource(R.string.chat_new_chat), leading = { MenuButton(onToggleSidebar) })
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
  val timeline by process.screenTimeline.collectAsStateWithLifecycle()
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
  timeline.pendingUi?.let { request -> ExtensionUiDialog(request) { controller.respondUi(request.id, it) } }
}
