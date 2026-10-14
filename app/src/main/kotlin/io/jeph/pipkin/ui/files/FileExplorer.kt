package io.jeph.pipkin.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.GitCommit
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.git.ChangesPane
import io.jeph.pipkin.ui.git.ExplorerTab
import io.jeph.pipkin.ui.git.GitLoss
import io.jeph.pipkin.ui.git.GitWorkspace
import io.jeph.pipkin.ui.git.GitWorkspaces
import io.jeph.pipkin.ui.git.TrustPrompt
import io.jeph.pipkin.ui.git.shownExplorerTab
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/** Paseo's mobile explorer toggle: Lucide `panel-right`, muted, at the end of a workspace header. */
@Composable
internal fun ExplorerToggle(onClick: () -> Unit) {
  IconButton28(
    icon = R.drawable.ic_lucide_panel_right,
    contentDescription = stringResource(R.string.files_explorer_open),
    onClick = onClick,
    tint = pipkinColors.foregroundMuted,
  )
}

/**
 * Paseo's compact explorer: a full-width panel beside the workspace with the Changes and Files tabs and a close
 * button. Files has a toolbar to collapse every folder, create a folder at the top of the workspace, show or hide dot
 * files and refresh, and the tree of the workspace folder [cwd]. Folders expand in place, listed as they open; tapping
 * a file calls [onOpenFile] with its workspace-relative path. Each workspace keeps its own expanded folders and scroll
 * position, saved as [ExplorerTrees] says. The tree is listed when the panel [open]s, and again on each later opening,
 * since Pi may have changed the files meanwhile; failures other than the top folder's show on [snackbar].
 *
 * Changes is there only while the workspace root is a git repository and Termux has git, which is detected when the
 * panel opens or moves to another workspace and on either tab's refresh; nothing polls. The tab last chosen in each
 * workspace is remembered, Changes until one is; while Changes isn't there Files shows without forgetting it. When
 * Changes goes away while the panel is open, [snackbar] says why. Tapping a commit in Changes calls [onOpenCommit].
 */
@Composable
internal fun ExplorerPanel(
  cwd: String?,
  open: Boolean,
  selectedPath: String?,
  snackbar: SnackbarStackState,
  onClose: () -> Unit,
  onOpenFile: (path: String) -> Unit,
  onOpenCommit: (GitCommit) -> Unit,
  modifier: Modifier = Modifier,
) {
  val graph = LocalContext.current.appGraph
  val trees = rememberSaveable(saver = ExplorerTrees.saver(graph.fileHost)) { ExplorerTrees(graph.fileHost) }
  val repositories = remember(graph) { GitWorkspaces(graph.gitHost) }
  val folder = cwd?.let(::folderKey)
  val state = folder?.let { trees[it] }
  val repository = folder?.let { repositories[it] }
  val trusted = folder in graph.preferences.trustedRepositories.collectAsStateWithLifecycle().value
  val explorerTabs by graph.preferences.explorerTabs.collectAsStateWithLifecycle()
  val changesAvailable = repository?.detection?.available == true
  val shownTab = shownExplorerTab(explorerTabs[folder], changesAvailable)
  val scope = rememberCoroutineScope()
  val detect = rememberDetection(repository, trusted, snackbar)
  LaunchedEffect(state, open) { if (open) state?.refresh() }
  LaunchedEffect(repository, open) { if (open) detect() }
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(pipkinColors.sidebar)
      .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
      .imePadding(),
  ) {
    ExplorerHeader(
      tabs = if (changesAvailable) ExplorerTab.entries else listOf(ExplorerTab.Files),
      shown = shownTab,
      onSelect = { tab -> folder?.let { graph.preferences.setExplorerTab(it, tab) } },
      onClose = onClose,
    )
    val content = Modifier.weight(1f)
    val refreshFiles = {
      scope.launch { state?.refresh() }
      scope.launch { detect() }
    }
    when {
      repository == null || shownTab == ExplorerTab.Files ->
        FilesPane(state, selectedPath, snackbar, onRefresh = { refreshFiles() }, onOpenFile, content)

      !trusted -> TrustPrompt(
        onTrust = { graph.preferences.trustRepository(repository.root) },
        onNotNow = { graph.preferences.setExplorerTab(repository.root, ExplorerTab.Files) },
        modifier = content,
      )

      else -> ChangesPane(
        workspace = repository,
        open = open,
        snackbar = snackbar,
        onDetect = detect,
        onOpenCommit = onOpenCommit,
        modifier = content,
      )
    }
  }
}

/**
 * Detects [repository] again, reading it with git only while [trusted], and says on [snackbar] why Changes went away
 * when it did.
 */
@Composable
private fun rememberDetection(
  repository: GitWorkspace?,
  trusted: Boolean,
  snackbar: SnackbarStackState,
): suspend () -> Unit {
  val scope = rememberCoroutineScope()
  val notRepository = stringResource(R.string.git_no_longer_repository)
  val gitMissing = stringResource(R.string.git_no_longer_installed)
  return remember(repository, trusted, notRepository, gitMissing) {
    {
      val message = when (repository?.detect(trusted)) {
        GitLoss.NotRepository -> notRepository
        GitLoss.GitMissing -> gitMissing
        null -> null
      }
      // The snackbar shows on its own, so a refresh that detected the loss needn't wait the 4 seconds it shows for.
      if (message != null) scope.launch { snackbar.show(message) }
    }
  }
}

/** The Files tab: its toolbar over the workspace's tree; failures other than the top folder's show on [snackbar]. */
@Composable
private fun FilesPane(
  state: FileExplorerState?,
  selectedPath: String?,
  snackbar: SnackbarStackState,
  onRefresh: () -> Unit,
  onOpenFile: (path: String) -> Unit,
  modifier: Modifier,
) {
  val graph = LocalContext.current.appGraph
  val showHidden by graph.preferences.showHiddenFiles.collectAsStateWithLifecycle()
  val sort by graph.preferences.explorerSort.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val report = { error: String? -> if (error != null) scope.launch { snackbar.show(error) } }
  val entries = if (state == null) {
    emptyList()
  } else {
    remember(state.directories, state.expanded, showHidden, sort) {
      explorerRows(state.directories, state.expanded, showHidden, sort)
    }
  }
  val anyShownExpanded = state != null && !state.restoringScroll && showsExpandedFolder(entries, state.expanded)
  Column(modifier = modifier) {
    ExplorerToolbar(
      sort = sort,
      showHidden = showHidden,
      refreshing = state?.refreshing == true,
      enabled = state?.directories?.isNotEmpty() == true,
      canCollapseAll = anyShownExpanded,
      onCollapseAll = { state?.collapseAll(showHidden, sort) },
      onNewFolder = { state?.creatingFolder = true },
      onRefresh = onRefresh,
    )
    if (state == null) return@Column
    LaunchedEffect(state, entries, state.relisted, state.refreshing) {
      if (!state.refreshing) state.restoreScroll(entries)
    }
    ExplorerTree(
      state = state,
      entries = entries,
      selectedPath = selectedPath,
      onToggle = { folder -> scope.launch { report(state.toggle(folder)) } },
      onOpenFile = onOpenFile,
      onCreateFolder = { name -> scope.launch { report(state.createFolder(name)) } },
      onRetry = { scope.launch { state.refresh() } },
      modifier = Modifier.weight(1f),
    )
  }
}

/** Paseo's explorer tabs, the shown one highlighted, and the close button. */
@Composable
private fun ExplorerHeader(
  tabs: List<ExplorerTab>,
  shown: ExplorerTab,
  onSelect: (ExplorerTab) -> Unit,
  onClose: () -> Unit,
) {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .padding(top = PipkinTokens.Spacing.s8)
      .fillMaxWidth()
      .height(56.dp)
      .padding(start = PipkinTokens.Spacing.s8, end = PipkinTokens.Spacing.s12),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    for (tab in tabs) ExplorerTabButton(tab, active = tab == shown, onClick = { onSelect(tab) })
    Spacer(modifier = Modifier.weight(1f))
    IconButton28(
      icon = R.drawable.ic_lucide_x,
      contentDescription = stringResource(R.string.files_explorer_close),
      onClick = onClose,
      tint = colors.foregroundMuted,
    )
  }
  HorizontalDivider(thickness = 1.dp, color = colors.border)
}

/** Paseo's explorer tab: its name, muted unless it's the [active] one, which is highlighted. */
@Composable
private fun ExplorerTabButton(tab: ExplorerTab, active: Boolean, onClick: () -> Unit) {
  val colors = pipkinColors
  Text(
    text = stringResource(
      when (tab) {
        ExplorerTab.Changes -> R.string.git_explorer_changes
        ExplorerTab.Files -> R.string.files_explorer_title
      },
    ),
    modifier = Modifier
      .clip(RoundedCornerShape(PipkinTokens.Radius.md))
      .background(if (active) sidebarHighlight() else Color.Transparent)
      .clickable(onClick = onClick)
      .semantics { selected = active }
      .padding(PipkinTokens.Spacing.s8),
    color = if (active) colors.foreground else colors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.base,
  )
}

/**
 * Paseo's Files toolbar: the sort key, which a tap moves to the next one, and beside it the direction, which a tap
 * reverses; then Collapse all while [canCollapseAll], New folder, the dot-file toggle (highlighted while they're
 * hidden) and Refresh. Collapse all comes and goes at the start of that group, so the buttons after it stay put. The
 * sort and dot-file choices are saved to preferences directly.
 */
@Composable
private fun ExplorerToolbar(
  sort: ExplorerSort,
  showHidden: Boolean,
  refreshing: Boolean,
  enabled: Boolean,
  canCollapseAll: Boolean,
  onCollapseAll: () -> Unit,
  onNewFolder: () -> Unit,
  onRefresh: () -> Unit,
) {
  val colors = pipkinColors
  val preferences = LocalContext.current.appGraph.preferences
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(36.dp)
      .padding(start = PipkinTokens.Spacing.s8, end = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(1.dp),
  ) {
    SortKeyButton(sort.key, onClick = { preferences.setExplorerSort(sort.next()) })
    IconButton28(
      icon = if (sort.descending) R.drawable.ic_lucide_arrow_down else R.drawable.ic_lucide_arrow_up,
      contentDescription = stringResource(
        if (sort.descending) R.string.files_sort_descending else R.string.files_sort_ascending,
      ),
      onClick = { preferences.setExplorerSort(sort.reversed()) },
      tint = colors.foregroundExtraMuted,
    )
    Spacer(modifier = Modifier.weight(1f))
    if (canCollapseAll) {
      IconButton28(
        icon = R.drawable.ic_lucide_chevrons_down_up,
        contentDescription = stringResource(R.string.files_collapse_all),
        onClick = onCollapseAll,
        tint = colors.foregroundExtraMuted,
      )
    }
    IconButton28(
      icon = R.drawable.ic_lucide_folder_plus,
      contentDescription = stringResource(R.string.files_new_folder),
      onClick = onNewFolder,
      enabled = enabled,
      tint = colors.foregroundExtraMuted,
    )
    IconButton28(
      icon = if (showHidden) R.drawable.ic_lucide_eye else R.drawable.ic_lucide_eye_off,
      contentDescription = stringResource(if (showHidden) R.string.files_hide_hidden else R.string.files_show_hidden),
      onClick = { preferences.setShowHiddenFiles(!showHidden) },
      background = if (showHidden) Color.Transparent else sidebarHighlight(),
      tint = colors.foregroundExtraMuted,
    )
    if (refreshing) {
      Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Spinner(PipkinTokens.IconSize.md, stringResource(R.string.files_refreshing))
      }
    } else {
      IconButton28(
        icon = R.drawable.ic_lucide_refresh_cw,
        contentDescription = stringResource(R.string.files_refresh),
        onClick = onRefresh,
        enabled = enabled,
        tint = colors.foregroundExtraMuted,
      )
    }
  }
  HorizontalDivider(thickness = 1.dp, color = colors.border)
}

/**
 * Paseo's sort trigger: the current key's name, small and muted, in a 24dp pill that a tap moves to the next key. The
 * pill is as wide as the longest key name, so the direction arrow beside it stays put as the key changes.
 */
@Composable
private fun SortKeyButton(key: SortKey, onClick: () -> Unit) {
  val label = sortKeyLabel(key)
  val description = stringResource(R.string.files_sort_by, label)
  Box(
    modifier = Modifier
      .height(24.dp)
      .clip(RoundedCornerShape(PipkinTokens.Radius.base))
      .clickable(onClick = onClick)
      .semantics { contentDescription = description }
      .padding(horizontal = PipkinTokens.Spacing.s4),
    contentAlignment = Alignment.Center,
  ) {
    for (other in SortKey.entries) {
      if (other != key) SortKeyLabel(sortKeyLabel(other), Modifier.alpha(0f).clearAndSetSemantics {})
    }
    SortKeyLabel(label)
  }
}

@Composable
private fun SortKeyLabel(text: String, modifier: Modifier = Modifier) {
  Text(text = text, modifier = modifier, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
}

@Composable
private fun sortKeyLabel(key: SortKey): String = stringResource(
  when (key) {
    SortKey.Name -> R.string.files_sort_name
    SortKey.Size -> R.string.files_sort_size
    SortKey.Date -> R.string.files_sort_date
  },
)
