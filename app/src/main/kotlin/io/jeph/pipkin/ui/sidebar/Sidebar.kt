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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ChatKey
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.Workspace
import io.jeph.pipkin.data.sameFolder
import io.jeph.pipkin.data.workspaces
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Paseo's mobile sidebar: chats grouped by workspace folder, each group collapsible, with a
 * health/actions footer.
 */
@Composable
internal fun Sidebar(
  actions: ShellActions,
  onClose: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenOnboarding: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val graph = LocalContext.current.appGraph
  val sessions by graph.sessionRepository.sessions.collectAsStateWithLifecycle()
  val added by graph.preferences.workspaces.collectAsStateWithLifecycle()
  val collapsed by graph.preferences.collapsedWorkspaces.collectAsStateWithLifecycle()
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val home = hello?.home?.takeIf { it.isNotEmpty() } ?: actions.home
  val workspaces = remember(sessions, added) { workspaces(sessions, added) }
  var sheetFor by remember { mutableStateOf<SessionInfo?>(null) }
  var pickerOpen by rememberSaveable { mutableStateOf(false) }
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
      onOpen = { if (actions.openSession(it)) onClose() },
      onNewChatIn = { cwd ->
        if (actions.newChat(cwd)) {
          graph.preferences.setLastProjectCwd(cwd)
          onClose()
        }
      },
      onToggle = { cwd -> graph.preferences.setWorkspaceCollapsed(cwd, cwd !in collapsed) },
      onActions = { sheetFor = it },
      modifier = Modifier.weight(1f),
    )
    SidebarFooter(
      onAddWorkspace = { pickerOpen = true },
      onOpenSettings = onOpenSettings,
      onOpenOnboarding = onOpenOnboarding,
    )
  }
  sheetFor?.let { session ->
    SessionActions(
      session = session,
      onDismiss = { sheetFor = null },
      onRename = { actions.rename(session, it) },
      onDelete = { actions.delete(session) },
    )
  }
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
  onOpen: (SessionInfo) -> Unit,
  onNewChatIn: (cwd: String) -> Unit,
  onToggle: (cwd: String) -> Unit,
  onActions: (SessionInfo) -> Unit,
  modifier: Modifier = Modifier,
) {
  val registry = LocalContext.current.appGraph.registry
  val processes by registry.processes.collectAsStateWithLifecycle()
  val visible by registry.visible.collectAsStateWithLifecycle()
  val visiblePath by remember(visible) { visible?.sessionFile ?: MutableStateFlow(null) }
    .collectAsStateWithLifecycle()
  if (workspaces.isEmpty()) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Text(
        text = stringResource(R.string.shell_sidebar_empty),
        color = pipkinColors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.base,
      )
    }
    return
  }
  LazyColumn(modifier = modifier.fillMaxWidth()) {
    for (workspace in workspaces) {
      val cwd = workspace.cwd
      val isCollapsed = cwd in collapsed
      item(key = "workspace:$cwd", contentType = "workspace") {
        WorkspaceHeader(
          name = projectName(cwd, home),
          chatCount = workspace.chats.size,
          collapsed = isCollapsed,
          onToggle = { onToggle(cwd) },
          onNewChat = { onNewChatIn(cwd) },
        )
      }
      if (isCollapsed) continue
      items(workspace.chats, key = { it.path }, contentType = { "session" }) { session ->
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
      icon = R.drawable.ic_lucide_plus,
      contentDescription = stringResource(R.string.shell_new_chat_in_project, name),
      onClick = onNewChat,
      tint = colors.foregroundMuted,
    )
  }
}
