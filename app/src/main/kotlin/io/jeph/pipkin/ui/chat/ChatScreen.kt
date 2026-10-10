package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** The sheet or dialog the chat screen shows over the stream; at most one at a time. */
private sealed interface ChatSheet {
  data object Actions : ChatSheet

  data object Model : ChatSheet

  data object Rename : ChatSheet

  data object ConfirmReload : ChatSheet

  data object ConfirmDelete : ChatSheet

  /** The detail sheet of the thinking block or tool call with this timeline key. */
  data class Tool(val key: String) : ChatSheet
}

/**
 * One chat: header, stream and composer for [process]; a null process shows a starting placeholder.
 */
@Composable
internal fun ChatScreen(
  process: AgentProcess?,
  onToggleSidebar: () -> Unit,
  onNewChat: (cwd: String) -> Unit,
  onOpenSettings: () -> Unit,
  onChatDeleted: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Outlive a process swap, so a message about the closed chat (delete failed) still shows.
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  if (process == null) {
    PlaceholderChat(onToggleSidebar, modifier)
    return
  }
  val context = LocalContext.current
  val controller = remember(process) {
    ChatController(process, context.appGraph, context.applicationContext, scope, snackbar)
  }
  LaunchedEffect(controller) { controller.collectEffects() }
  var sheet by remember(process) { mutableStateOf<ChatSheet?>(null) }
  ChatContent(controller, snackbar, onToggleSidebar, onOpenSettings, onSheet = { sheet = it }, modifier)
  ChatSheets(controller, sheet, onSheet = { sheet = it }, onNewChat)
  ChatDialogs(controller, sheet, onSheet = { sheet = it }, onChatDeleted)
}

@Composable
private fun ChatContent(
  controller: ChatController,
  snackbar: SnackbarHostState,
  onToggleSidebar: () -> Unit,
  onOpenSettings: () -> Unit,
  onSheet: (ChatSheet) -> Unit,
  modifier: Modifier,
) {
  val process = controller.process
  val timeline by process.timeline.state.collectAsStateWithLifecycle()
  val processState by process.state.collectAsStateWithLifecycle()
  val busy by process.busy.collectAsStateWithLifecycle()
  Column(modifier = modifier.fillMaxSize().background(pipkinColors.surface0)) {
    ChatHeader(process, onToggleSidebar, onActions = { onSheet(ChatSheet.Actions) })
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      ChatStream(
        process = process,
        timeline = timeline,
        processState = processState,
        onOpenTool = { onSheet(ChatSheet.Tool(it)) },
        onOpenSettings = onOpenSettings,
      )
      ChatSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (timeline.queue.isNotEmpty()) {
      QueueRows(timeline.queue, onClear = controller::clearQueue, Modifier.padding(bottom = PipkinTokens.Spacing.s8))
    }
    Composer(
      controller = controller,
      model = timeline.model,
      thinkingLevel = timeline.thinkingLevel,
      busy = busy,
      onOpenModelSheet = { onSheet(ChatSheet.Model) },
    )
  }
}

/** Session name (or the first message) over the project path, with `~` for the Termux home. */
@Composable
private fun ChatHeader(process: AgentProcess, onToggleSidebar: () -> Unit, onActions: () -> Unit) {
  val graph = LocalContext.current.appGraph
  val sessionName by process.sessionName.collectAsStateWithLifecycle()
  val sessionFile by process.sessionFile.collectAsStateWithLifecycle()
  val sessions by graph.sessionRepository.sessions.collectAsStateWithLifecycle()
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val firstMessage = sessions.firstOrNull { it.path == sessionFile }?.firstMessage?.takeIf { it.isNotBlank() }
  HeaderBar(
    title = sessionName ?: firstMessage ?: stringResource(R.string.chat_new_chat),
    subtitle = hello?.home?.let { tildePath(process.cwd, it) } ?: process.cwd,
    leading = { MenuButton(onToggleSidebar) },
    trailing = {
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
private fun ChatSnackbarHost(state: SnackbarHostState, modifier: Modifier) {
  val colors = pipkinColors
  SnackbarHost(hostState = state, modifier = modifier.padding(PipkinTokens.Spacing.s16)) { data ->
    Snackbar(
      snackbarData = data,
      shape = RoundedCornerShape(PipkinTokens.Radius.lg),
      containerColor = colors.surface3,
      contentColor = colors.foreground,
      actionColor = colors.accent,
    )
  }
}

@Composable
private fun ChatSheets(
  controller: ChatController,
  sheet: ChatSheet?,
  onSheet: (ChatSheet?) -> Unit,
  onNewChat: (cwd: String) -> Unit,
) {
  val process = controller.process
  val timeline by process.timeline.state.collectAsStateWithLifecycle()
  val busy by process.busy.collectAsStateWithLifecycle()
  val sessionFile by process.sessionFile.collectAsStateWithLifecycle()
  val copy = rememberCopyText()
  val dismiss = { onSheet(null) }
  when (sheet) {
    ChatSheet.Actions -> ChatActionsSheet(
      actions = ChatActions(
        onNewChat = { onNewChat(process.cwd) },
        onRename = { onSheet(ChatSheet.Rename) },
        onReload = { if (busy) onSheet(ChatSheet.ConfirmReload) else controller.reload() },
        onCopyPath = sessionFile?.let { path -> { copy(path) } },
        onDelete = { onSheet(ChatSheet.ConfirmDelete) },
      ),
      onDismiss = dismiss,
    )

    ChatSheet.Model -> ModelSheet(controller, timeline.model, timeline.thinkingLevel, onDismiss = dismiss)

    is ChatSheet.Tool -> {
      val item = timeline.items.firstOrNull { it.key == sheet.key }
      if (item != null) ToolCallSheet(item, onDismiss = dismiss)
    }

    else -> Unit
  }
}

@Composable
private fun ChatDialogs(
  controller: ChatController,
  sheet: ChatSheet?,
  onSheet: (ChatSheet?) -> Unit,
  onChatDeleted: () -> Unit,
) {
  val process = controller.process
  val timeline by process.timeline.state.collectAsStateWithLifecycle()
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

    ChatSheet.ConfirmDelete -> ConfirmDialog(
      title = stringResource(R.string.chat_delete_confirm_title),
      text = stringResource(R.string.chat_delete_confirm_text),
      confirmLabel = stringResource(R.string.chat_delete),
      onConfirm = {
        dismiss()
        controller.delete(onChatDeleted)
      },
      onDismiss = dismiss,
      destructive = true,
    )

    else -> Unit
  }
  timeline.pendingUi?.let { request -> ExtensionUiDialog(request) { controller.respondUi(request.id, it) } }
}
