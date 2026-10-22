package io.jeph.pipkin.ui.sidebar

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.Workspace
import io.jeph.pipkin.data.sameFolder
import io.jeph.pipkin.data.workspaces
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.HeaderButton
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.PotIcon
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.SwipeAway
import io.jeph.pipkin.ui.files.sidebarHighlight
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.rememberPaneItems
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
      callbacks = WorkspaceListCallbacks(
        onOpen = { cwd -> showThen(cwd) { actions.openWorkspace(pane, cwd) } },
        onNewChatIn = { cwd -> showThen(cwd) { actions.newChat(pane, cwd) } },
        onActions = { menuFor = it },
        onAddWorkspace = { pickerOpen = true },
      ),
      modifier = Modifier.weight(1f),
    )
    SidebarFooter(
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

/** The Add workspace row's key in the list, which can't be a workspace's folder. */
private const val ADD_WORKSPACE_KEY = "add-workspace"

/**
 * Removing a workspace from the sidebar asks first, then slides its row out and removes it, archiving its chats, once
 * the row is gone.
 */
private class WorkspaceRemoval(private val actions: ShellActions) {
  /** Folders of rows sliding out. */
  var leaving by mutableStateOf(emptySet<String>())
    private set

  /** A workspace waiting for "Remove". */
  var confirming by mutableStateOf<RemovalRequest?>(null)
    private set

  fun askToRemove(workspace: Workspace) {
    confirming = RemovalRequest(workspace, actions.workingIn(workspace.cwd), actions.terminalsIn(workspace.cwd))
  }

  fun confirm() {
    val workspace = confirming?.workspace ?: return
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

/** A workspace to remove, with how many of its chats are working and how many terminals it has open. */
private class RemovalRequest(val workspace: Workspace, val working: Int, val terminals: Int)

@Composable
private fun RemoveWorkspaceDialog(removal: WorkspaceRemoval, home: String) {
  val request = removal.confirming ?: return
  val chats = request.workspace.chats.size
  val working = request.working
  val archived = if (chats == 0) {
    stringResource(R.string.archive_remove_text_empty)
  } else {
    pluralStringResource(R.plurals.archive_remove_text, chats, chats)
  }
  // A chat Pi just started may be working before its session file is listed, so working can exceed chats.
  val stopping = when {
    working == 0 -> null
    working == 1 && chats <= 1 -> stringResource(R.string.archive_remove_working_single)
    else -> pluralStringResource(R.plurals.archive_remove_working, working, working)
  }
  val closing = request.terminals.takeIf { it > 0 }?.let {
    pluralStringResource(R.plurals.archive_remove_terminals, it, it)
  }
  val text = listOfNotNull(archived, stopping, closing).joinToString(" ")
  ConfirmDialog(
    title = stringResource(R.string.archive_remove_title, projectName(request.workspace.cwd, home)),
    text = text,
    confirmLabel = stringResource(R.string.archive_remove_confirm),
    onConfirm = removal::confirm,
    onDismiss = removal::cancel,
    destructive = true,
    icon = R.drawable.ic_lucide_folder_minus,
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
      .heightIn(min = 64.dp)
      .padding(start = 20.dp, end = PipkinTokens.Spacing.s12),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    PotIcon(size = 26.dp)
    Text(
      text = stringResource(R.string.shell_sidebar_title),
      modifier = Modifier.weight(1f),
      color = pipkinColors.foreground,
      fontSize = 26.sp,
      fontWeight = FontWeight.ExtraBold,
      letterSpacing = (-0.025).em,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    HeaderButton(
      icon = R.drawable.ic_lucide_x,
      contentDescription = stringResource(R.string.shell_sidebar_close),
      onClick = onClose,
      filled = true,
    )
  }
}

/** What the workspace list's rows do. */
private class WorkspaceListCallbacks(
  val onOpen: (cwd: String) -> Unit,
  val onNewChatIn: (cwd: String) -> Unit,
  val onActions: (Workspace) -> Unit,
  val onAddWorkspace: () -> Unit,
)

@Composable
private fun WorkspaceList(
  pane: Pane,
  workspaces: List<Workspace>,
  home: String,
  removal: WorkspaceRemoval,
  callbacks: WorkspaceListCallbacks,
  modifier: Modifier = Modifier,
) {
  if (workspaces.isEmpty()) {
    NoWorkspaces(callbacks.onAddWorkspace, modifier)
    return
  }
  val graph = LocalContext.current.appGraph
  val processes by graph.registry.processes.collectAsStateWithLifecycle()
  val openTabs by graph.tabs.tabs.collectAsStateWithLifecycle()
  val items = rememberPaneItems()
  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(
      start = PipkinTokens.Spacing.s12,
      end = PipkinTokens.Spacing.s12,
      bottom = PipkinTokens.Spacing.s12,
    ),
  ) {
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
          folder = tildePath(cwd, home),
          cwd = cwd,
          shown = items[pane]?.let { sameFolder(it.cwd, cwd) } == true,
          status = WorkspaceStatus(
            activity = workspaceActivity(
              remember(processes, cwd) {
                processes.values.filter { sameFolder(it.cwd, cwd) }
              },
            ),
            tabs = workspace.chats.size + openTabs.count { sameFolder(it.cwd, cwd) },
          ),
          onOpen = { callbacks.onOpen(cwd) },
          onNewChat = { callbacks.onNewChatIn(cwd) },
          onActions = { callbacks.onActions(workspace) },
        )
      }
    }
    item(key = ADD_WORKSPACE_KEY) {
      AddWorkspaceRow(onClick = callbacks.onAddWorkspace, modifier = Modifier.animateItem())
    }
  }
}

/** The sidebar with no workspaces yet: a note over the Add workspace row. */
@Composable
private fun NoWorkspaces(onAddWorkspace: () -> Unit, modifier: Modifier) {
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = PipkinTokens.Spacing.s12),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s16, Alignment.CenterVertically),
  ) {
    Text(
      text = stringResource(R.string.shell_sidebar_empty),
      modifier = Modifier.fillMaxWidth(),
      color = pipkinColors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.lg,
      textAlign = TextAlign.Center,
    )
    AddWorkspaceRow(onClick = onAddWorkspace)
  }
}
