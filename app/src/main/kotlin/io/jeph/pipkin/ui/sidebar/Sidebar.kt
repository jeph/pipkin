package io.jeph.pipkin.ui.sidebar

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.Workspace
import io.jeph.pipkin.data.sameFolder
import io.jeph.pipkin.data.workspaces
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.SwipeAway
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.workspace.Pane
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * [pane]'s sidebar: the workspace folders, with a health/actions footer. Tapping a workspace shows its chat in [pane];
 * the chats in it are in the chat header's tab menu. A workspace is removed, archiving its chats, from its own sheet,
 * which also opens it in split screen when [onShownInSplit] is given, calling it once the second pane shows it.
 */
@Composable
internal fun Sidebar(
  pane: Pane,
  actions: ShellActions,
  onShownInSplit: (() -> Unit)?,
  onClose: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenArchive: () -> Unit,
  onOpenOnboarding: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val graph = LocalContext.current.appGraph
  val sessions by graph.sessionArchive.active.collectAsStateWithLifecycle()
  val added by graph.preferences.workspaces.collectAsStateWithLifecycle()
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val home = hello?.home?.takeIf { it.isNotEmpty() } ?: actions.home
  val workspaces = remember(sessions, added) { workspaces(sessions, added) }
  val removal = remember(actions) { WorkspaceRemoval(actions) }
  var menuFor by remember { mutableStateOf<Workspace?>(null) }
  var pickerOpen by rememberSaveable { mutableStateOf(false) }
  // Rows that left the list forget that they were leaving, so a workspace added back shows in place.
  LaunchedEffect(workspaces) { removal.forgetGone(workspaces) }
  val showThen = { cwd: String, open: () -> Boolean ->
    if (open()) {
      graph.preferences.setLastProjectCwd(cwd)
      onClose()
    }
  }
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(pipkinColors.sidebar)
      .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
  ) {
    SidebarHeader(onClose)
    WorkspaceList(
      pane = pane,
      workspaces = workspaces,
      home = home,
      removal = removal,
      onOpen = { cwd -> showThen(cwd) { actions.openWorkspace(pane, cwd) } },
      onNewChatIn = { cwd -> showThen(cwd) { actions.newChat(pane, cwd) } },
      onActions = { menuFor = it },
      modifier = Modifier.weight(1f),
    )
    SidebarFooter(
      onAddWorkspace = { pickerOpen = true },
      onOpenArchive = onOpenArchive,
      onOpenSettings = onOpenSettings,
      onOpenOnboarding = onOpenOnboarding,
    )
  }
  menuFor?.let { workspace ->
    WorkspaceActions(
      path = tildePath(workspace.cwd, home),
      onDismiss = { menuFor = null },
      onOpenInSplit = onShownInSplit?.let { shown ->
        {
          showThen(workspace.cwd) { actions.openWorkspace(Pane.Secondary, workspace.cwd).also { if (it) shown() } }
        }
      },
      onRemove = { removal.askToRemove(workspace) },
    )
  }
  RemoveWorkspaceDialog(removal, home)
  if (pickerOpen) AddWorkspacePicker(home, workspaces, onDismiss = { pickerOpen = false })
}

/** The add-workspace sheet over the sidebar, which adds the folder picked as a workspace. */
@Composable
private fun AddWorkspacePicker(home: String, workspaces: List<Workspace>, onDismiss: () -> Unit) {
  val preferences = LocalContext.current.appGraph.preferences
  AddWorkspaceSheet(
    home = home,
    isWorkspace = { cwd -> workspaces.any { sameFolder(it.cwd, cwd) } },
    onAdd = { cwd ->
      preferences.addWorkspace(cwd)
      onDismiss()
    },
    onDismiss = onDismiss,
  )
}

/** The sidebar's icon buttons: a 36dp target around a 20dp icon. */
@Composable
internal fun SidebarIconButton(
  @DrawableRes icon: Int,
  contentDescription: String,
  onClick: () -> Unit,
  tint: Color = pipkinColors.foreground,
) {
  IconButton28(
    icon = icon,
    contentDescription = contentDescription,
    onClick = onClick,
    modifier = Modifier.size(36.dp),
    tint = tint,
    iconSize = PipkinTokens.IconSize.lg,
  )
}

/**
 * Removing a workspace from the sidebar asks first, then slides its row out and removes it, archiving its chats, once
 * the row is gone.
 */
private class WorkspaceRemoval(private val actions: ShellActions) {
  /** Folders of rows sliding out. */
  var leaving by mutableStateOf(emptySet<String>())
    private set

  /** A workspace waiting for "Remove", with how many of its chats are working. */
  var confirming by mutableStateOf<Pair<Workspace, Int>?>(null)
    private set

  fun askToRemove(workspace: Workspace) {
    confirming = workspace to actions.workingIn(workspace.cwd)
  }

  fun confirm() {
    val (workspace, _) = confirming ?: return
    confirming = null
    leaving += workspace.cwd
  }

  fun cancel() {
    confirming = null
  }

  fun onLeft(cwd: String) {
    actions.removeWorkspace(cwd)
  }

  fun forgetGone(workspaces: List<Workspace>) {
    val shown = workspaces.mapTo(HashSet()) { it.cwd }
    if (leaving.any { it !in shown }) leaving = leaving intersect shown
  }
}

@Composable
private fun RemoveWorkspaceDialog(removal: WorkspaceRemoval, home: String) {
  val (workspace, working) = removal.confirming ?: return
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
    onConfirm = removal::confirm,
    onDismiss = removal::cancel,
    destructive = true,
  )
}

/** A workspace's sheet under its path: Open in split screen when [onOpenInSplit] is given, and Remove workspace. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceActions(path: String, onDismiss: () -> Unit, onOpenInSplit: (() -> Unit)?, onRemove: () -> Unit) {
  PipkinSheet(onDismissRequest = onDismiss) {
    // A long path keeps its end, the folder that tells workspaces apart.
    Text(
      text = path,
      modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
      color = pipkinColors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      maxLines = 1,
      overflow = TextOverflow.StartEllipsis,
    )
    if (onOpenInSplit != null) {
      SheetRow(
        label = stringResource(R.string.shell_split_open),
        icon = R.drawable.ic_lucide_columns_2,
        onClick = {
          onDismiss()
          onOpenInSplit()
        },
      )
    }
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
    LucideIcon(icon = R.drawable.ic_lucide_bot, contentDescription = null, size = PipkinTokens.IconSize.lg)
    Text(
      text = stringResource(R.string.shell_sidebar_title),
      modifier = Modifier.weight(1f),
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.lg,
    )
    SidebarIconButton(
      icon = R.drawable.ic_lucide_x,
      contentDescription = stringResource(R.string.shell_sidebar_close),
      onClick = onClose,
    )
  }
}

@Composable
private fun WorkspaceList(
  pane: Pane,
  workspaces: List<Workspace>,
  home: String,
  removal: WorkspaceRemoval,
  onOpen: (cwd: String) -> Unit,
  onNewChatIn: (cwd: String) -> Unit,
  onActions: (Workspace) -> Unit,
  modifier: Modifier = Modifier,
) {
  val registry = LocalContext.current.appGraph.registry
  val processes by registry.processes.collectAsStateWithLifecycle()
  val panes by registry.shown.collectAsStateWithLifecycle()
  if (workspaces.isEmpty()) {
    Text(
      text = stringResource(R.string.shell_sidebar_empty),
      modifier = modifier.fillMaxWidth().wrapContentHeight(),
      color = pipkinColors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.lg,
      textAlign = TextAlign.Center,
    )
    return
  }
  LazyColumn(modifier = modifier.fillMaxWidth()) {
    items(workspaces, key = { it.cwd }) { workspace ->
      val cwd = workspace.cwd
      SwipeAway(
        leaving = cwd in removal.leaving,
        onSwiped = {},
        onLeft = { removal.onLeft(cwd) },
        containerColor = pipkinColors.sidebar,
        modifier = Modifier.animateItem(),
        swipeEnabled = false,
      ) {
        WorkspaceRow(
          name = projectName(cwd, home),
          shown = panes[pane]?.let { sameFolder(it.cwd, cwd) } == true,
          working = anyWorking(remember(processes, cwd) { processes.values.filter { sameFolder(it.cwd, cwd) } }),
          onOpen = { onOpen(cwd) },
          onNewChat = { onNewChatIn(cwd) },
          onActions = { onActions(workspace) },
        )
      }
    }
  }
}

/** Whether any of [processes] has a Pi run going. */
@Composable
private fun anyWorking(processes: List<AgentProcess>): Boolean {
  val working = remember(processes) {
    if (processes.isEmpty()) flowOf(false) else combine(processes.map { it.busy }) { busy -> busy.any { it } }
  }
  return working.collectAsStateWithLifecycle(initialValue = false).value
}

/**
 * A workspace's row: its folder (the simmering pot while Pi works in one of its chats) and name, highlighted while
 * the sidebar's pane shows one of its chats, with its actions and New chat. Tapping it shows its chat; long-press opens
 * its actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceRow(
  name: String,
  shown: Boolean,
  working: Boolean,
  onOpen: () -> Unit,
  onNewChat: () -> Unit,
  onActions: () -> Unit,
) {
  val colors = pipkinColors
  // The light sidebar is surface2 itself, so its highlight steps up one surface.
  val highlight = if (colors.isDark) colors.surface2 else colors.surface3
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = PipkinTokens.Spacing.s8, vertical = PipkinTokens.Spacing.s2)
      .clip(RoundedCornerShape(PipkinTokens.Radius.lg))
      .background(if (shown) highlight else Color.Transparent)
      .combinedClickable(
        onClickLabel = stringResource(R.string.shell_workspace_open, name),
        onClick = onOpen,
        onLongClick = onActions,
      )
      .heightIn(min = 56.dp)
      .padding(start = PipkinTokens.Spacing.s12, end = PipkinTokens.Spacing.s4),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    Box(modifier = Modifier.size(PipkinTokens.IconSize.lg), contentAlignment = Alignment.Center) {
      if (working) {
        SimmeringPot(size = PipkinTokens.IconSize.lg)
      } else {
        LucideIcon(
          icon = R.drawable.ic_lucide_folder,
          contentDescription = null,
          size = PipkinTokens.IconSize.lg,
          tint = colors.foregroundMuted,
        )
      }
    }
    Text(
      text = name,
      modifier = Modifier.weight(1f),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.content,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    SidebarIconButton(
      icon = R.drawable.ic_lucide_ellipsis,
      contentDescription = stringResource(R.string.archive_workspace_actions, name),
      onClick = onActions,
      tint = colors.foregroundMuted,
    )
    SidebarIconButton(
      icon = R.drawable.ic_lucide_plus,
      contentDescription = stringResource(R.string.shell_new_chat_in_project, name),
      onClick = onNewChat,
      tint = colors.foregroundMuted,
    )
  }
}
