package io.jeph.pipkin.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ChatKey
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.Workspace
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.data.sameFolder
import io.jeph.pipkin.data.workspaces
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Paseo's mobile sidebar: chats grouped by workspace folder, each group collapsible, with a
 * health/actions footer. A chat is archived by swiping it right or from its actions sheet; a
 * workspace is removed, archiving its chats, from its own sheet.
 */
@Composable
internal fun Sidebar(
  actions: ShellActions,
  onClose: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenArchive: () -> Unit,
  onOpenOnboarding: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val graph = LocalContext.current.appGraph
  val sessions by graph.sessionArchive.active.collectAsStateWithLifecycle()
  val added by graph.preferences.workspaces.collectAsStateWithLifecycle()
  val collapsed by graph.preferences.collapsedWorkspaces.collectAsStateWithLifecycle()
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val home = hello?.home?.takeIf { it.isNotEmpty() } ?: actions.home
  val workspaces = remember(sessions, added) { workspaces(sessions, added) }
  val archiving = remember(actions) { SidebarArchiving(actions) }
  val menus = remember { SidebarMenus() }
  var pickerOpen by rememberSaveable { mutableStateOf(false) }
  // Rows that left the list forget that they were leaving, so one that comes back (Undo) shows in place.
  LaunchedEffect(workspaces) { archiving.forgetGone(workspaces) }
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(pipkinColors.sidebar)
      .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
  ) {
    SidebarHeader(onClose)
    WorkspaceList(
      workspaces = workspaces,
      collapsed = collapsed,
      home = home,
      archiving = archiving,
      onOpen = { if (actions.openSession(it)) onClose() },
      onNewChatIn = { cwd ->
        if (actions.newChat(cwd)) {
          graph.preferences.setLastProjectCwd(cwd)
          onClose()
        }
      },
      onToggle = { cwd -> graph.preferences.setWorkspaceCollapsed(cwd, cwd !in collapsed) },
      onActions = { menus.chat = it },
      onWorkspaceActions = { menus.workspace = it },
      modifier = Modifier.weight(1f),
    )
    SidebarFooter(
      onAddWorkspace = { pickerOpen = true },
      onOpenArchive = onOpenArchive,
      onOpenSettings = onOpenSettings,
      onOpenOnboarding = onOpenOnboarding,
    )
  }
  SidebarMenuSheets(menus, actions, archiving, home)
  if (pickerOpen) {
    AddWorkspaceSheet(
      home = home,
      isWorkspace = { cwd -> workspaces.any { sameFolder(it.cwd, cwd) } },
      onAdd = { cwd ->
        graph.preferences.addWorkspace(cwd)
        pickerOpen = false
      },
      onDismiss = { pickerOpen = false },
    )
  }
}

/** Which of the sidebar's action sheets is open: a chat's or a workspace's. */
private class SidebarMenus {
  var chat by mutableStateOf<SessionInfo?>(null)
  var workspace by mutableStateOf<Workspace?>(null)
}

@Composable
private fun SidebarMenuSheets(menus: SidebarMenus, actions: ShellActions, archiving: SidebarArchiving, home: String) {
  menus.chat?.let { session ->
    SessionActions(
      session = session,
      onDismiss = { menus.chat = null },
      onRename = { actions.rename(session, it) },
      onArchive = { archiving.archive(session) },
    )
  }
  menus.workspace?.let { workspace ->
    WorkspaceActions(
      name = projectName(workspace.cwd, home),
      onDismiss = { menus.workspace = null },
      onRemove = { archiving.askToRemove(workspace) },
    )
  }
  SidebarArchiveDialogs(archiving, home)
}

/** The sidebar's lazy-list keys: a workspace's folder row, and a chat's row by its session file. */
private fun workspaceKey(cwd: String) = "workspace:$cwd"

/**
 * Archiving from the sidebar. A row first slides out; once it is gone the chat is archived, or, when Pi is working
 * in it, the user is asked first and a cancelled row slides back. Removing a workspace asks first, then slides its
 * folder row and chats out together and removes it once they are gone.
 */
private class SidebarArchiving(private val actions: ShellActions) {
  /** Keys of rows sliding out, or out and waiting for the user. */
  var leaving by mutableStateOf(emptySet<String>())
    private set

  /** A slid-out chat Pi is working in, waiting for "Stop and archive". */
  var confirmingChat by mutableStateOf<SessionInfo?>(null)
    private set

  /** A workspace waiting for "Remove", with how many of its chats are working. */
  var confirmingWorkspace by mutableStateOf<Pair<Workspace, Int>?>(null)
    private set

  fun archive(session: SessionInfo) {
    leaving += session.path
  }

  fun onChatLeft(session: SessionInfo) {
    // A workspace removal slides its chats out too; it archives them once its folder row is gone.
    if (workspaceKey(folderKey(session.cwd)) in leaving) return
    if (actions.isWorking(session.path)) confirmingChat = session else actions.archive(session)
  }

  fun confirmChat() {
    confirmingChat?.let(actions::archive)
    confirmingChat = null
  }

  fun cancelChat() {
    confirmingChat?.let { leaving -= it.path }
    confirmingChat = null
  }

  fun askToRemove(workspace: Workspace) {
    confirmingWorkspace = workspace to actions.workingIn(workspace.cwd)
  }

  fun confirmRemove() {
    val (workspace, _) = confirmingWorkspace ?: return
    confirmingWorkspace = null
    leaving += workspace.chats.map { it.path } + workspaceKey(workspace.cwd)
  }

  fun cancelRemove() {
    confirmingWorkspace = null
  }

  fun onWorkspaceLeft(cwd: String) {
    actions.removeWorkspace(cwd)
  }

  fun forgetGone(workspaces: List<Workspace>) {
    val shown = workspaces.flatMap { workspace ->
      workspace.chats.map { it.path } + workspaceKey(workspace.cwd)
    }.toSet()
    if (leaving.any { it !in shown }) leaving = leaving intersect shown
  }
}

@Composable
private fun SidebarArchiveDialogs(archiving: SidebarArchiving, home: String) {
  if (archiving.confirmingChat != null) {
    ConfirmDialog(
      title = stringResource(R.string.archive_stop_title),
      text = stringResource(R.string.archive_stop_text),
      confirmLabel = stringResource(R.string.archive_stop_confirm),
      onConfirm = archiving::confirmChat,
      onDismiss = archiving::cancelChat,
      destructive = true,
    )
  }
  val (workspace, working) = archiving.confirmingWorkspace ?: return
  val chats = workspace.chats.size
  val archived = if (chats == 0) {
    stringResource(R.string.archive_remove_text_empty)
  } else {
    pluralStringResource(R.plurals.archive_remove_text, chats, chats)
  }
  // A chat Pi just started may be working before its session file is listed, so working can exceed chats.
  val text = when {
    working == 0 -> archived
    working == 1 && chats <= 1 -> archived + " " + stringResource(R.string.archive_remove_working_single)
    else -> archived + " " + pluralStringResource(R.plurals.archive_remove_working, working, working)
  }
  ConfirmDialog(
    title = stringResource(R.string.archive_remove_title, projectName(workspace.cwd, home)),
    text = text,
    confirmLabel = stringResource(R.string.archive_remove_confirm),
    onConfirm = archiving::confirmRemove,
    onDismiss = archiving::cancelRemove,
    destructive = true,
  )
}

/** A workspace's sheet: Remove workspace. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceActions(name: String, onDismiss: () -> Unit, onRemove: () -> Unit) {
  PipkinSheet(onDismissRequest = onDismiss) {
    Text(
      text = name,
      modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
      color = pipkinColors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    SheetRow(
      label = stringResource(R.string.archive_remove_workspace),
      icon = R.drawable.ic_lucide_folder_minus,
      labelColor = pipkinColors.destructive,
      onClick = {
        onDismiss()
        onRemove()
      },
    )
  }
}

@Composable
private fun SidebarHeader(onClose: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    LucideIcon(icon = R.drawable.ic_lucide_bot, contentDescription = null)
    Text(
      text = stringResource(R.string.shell_sidebar_title),
      modifier = Modifier.weight(1f),
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.base,
    )
    IconButton28(
      icon = R.drawable.ic_lucide_x,
      contentDescription = stringResource(R.string.shell_sidebar_close),
      onClick = onClose,
      modifier = Modifier.size(32.dp),
    )
  }
}

@Composable
private fun WorkspaceList(
  workspaces: List<Workspace>,
  collapsed: Set<String>,
  home: String,
  archiving: SidebarArchiving,
  onOpen: (SessionInfo) -> Unit,
  onNewChatIn: (cwd: String) -> Unit,
  onToggle: (cwd: String) -> Unit,
  onActions: (SessionInfo) -> Unit,
  onWorkspaceActions: (Workspace) -> Unit,
  modifier: Modifier = Modifier,
) {
  val registry = LocalContext.current.appGraph.registry
  val processes by registry.processes.collectAsStateWithLifecycle()
  val visible by registry.visible.collectAsStateWithLifecycle()
  val visiblePath by remember(visible) { visible?.sessionFile ?: MutableStateFlow(null) }
    .collectAsStateWithLifecycle()
  if (workspaces.isEmpty()) {
    Text(
      text = stringResource(R.string.shell_sidebar_empty),
      modifier = modifier.fillMaxWidth().wrapContentHeight(),
      color = pipkinColors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
      textAlign = TextAlign.Center,
    )
    return
  }
  LazyColumn(modifier = modifier.fillMaxWidth()) {
    for (workspace in workspaces) {
      val cwd = workspace.cwd
      val isCollapsed = cwd in collapsed
      item(key = workspaceKey(cwd), contentType = "workspace") {
        SwipeToArchive(
          leaving = workspaceKey(cwd) in archiving.leaving,
          onSwiped = {},
          onLeft = { archiving.onWorkspaceLeft(cwd) },
          containerColor = pipkinColors.sidebar,
          modifier = Modifier.animateItem(),
          swipeEnabled = false,
        ) {
          WorkspaceHeader(
            name = projectName(cwd, home),
            chatCount = workspace.chats.size,
            collapsed = isCollapsed,
            onToggle = { onToggle(cwd) },
            onNewChat = { onNewChatIn(cwd) },
            onActions = { onWorkspaceActions(workspace) },
          )
        }
      }
      if (isCollapsed) continue
      items(workspace.chats, key = { it.path }, contentType = { "session" }) { session ->
        SwipeToArchive(
          leaving = session.path in archiving.leaving,
          onSwiped = { archiving.archive(session) },
          onLeft = { archiving.onChatLeft(session) },
          containerColor = pipkinColors.sidebar,
          modifier = Modifier.animateItem(),
        ) {
          SessionRow(
            session = session,
            process = processes[ChatKey.session(session.path)],
            selected = session.path == visiblePath,
            onOpen = { onOpen(session) },
            onActions = { onActions(session) },
          )
        }
      }
    }
  }
}

/**
 * A workspace's folder row. Tapping the name hides or shows its chats. The chevron points down while
 * they show and right while they are hidden, when the number of hidden chats follows the name.
 */
@Composable
private fun WorkspaceHeader(
  name: String,
  chatCount: Int,
  collapsed: Boolean,
  onToggle: () -> Unit,
  onNewChat: () -> Unit,
  onActions: () -> Unit,
) {
  val colors = pipkinColors
  val toggleLabel = stringResource(
    if (collapsed) R.string.shell_workspace_expand else R.string.shell_workspace_collapse,
    name,
  )
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 44.dp)
      .padding(end = PipkinTokens.Spacing.s12, top = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    Row(
      modifier = Modifier
        .weight(1f)
        .heightIn(min = 36.dp)
        .clickable(onClickLabel = toggleLabel, onClick = onToggle)
        .padding(start = PipkinTokens.Spacing.s12, end = PipkinTokens.Spacing.s8),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
    ) {
      LucideIcon(
        icon = R.drawable.ic_lucide_chevron_down,
        contentDescription = null,
        modifier = Modifier.rotate(if (collapsed) -90f else 0f),
        size = PipkinTokens.IconSize.sm,
        tint = colors.foregroundMuted,
      )
      LucideIcon(icon = R.drawable.ic_lucide_folder, contentDescription = null, tint = colors.foregroundMuted)
      Text(
        text = name,
        modifier = Modifier.weight(1f, fill = false),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (collapsed && chatCount > 0) {
        Text(text = chatCount.toString(), color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
      }
    }
    IconButton28(
      icon = R.drawable.ic_lucide_ellipsis,
      contentDescription = stringResource(R.string.archive_workspace_actions, name),
      onClick = onActions,
      tint = colors.foregroundMuted,
    )
    IconButton28(
      icon = R.drawable.ic_lucide_plus,
      contentDescription = stringResource(R.string.shell_new_chat_in_project, name),
      onClick = onNewChat,
      tint = colors.foregroundMuted,
    )
  }
}
