package io.jeph.pipkin.ui.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.FileEntry
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.SnackbarStackState
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
 * Paseo's compact file explorer: a full-width panel beside the workspace with a Files header and close button, a
 * toolbar to create a folder at the top of the workspace, show or hide dot files and refresh, and the tree of the
 * workspace folder [cwd]. Folders expand in place, listed as they open; tapping a file calls [onOpenFile] with its
 * workspace-relative path. Each workspace keeps its own tree while the panel stays composed. The tree is listed when
 * the panel [open]s, and again on each later opening, since Pi may have changed the files meanwhile; failures other
 * than the top folder's show on [snackbar].
 */
@Composable
internal fun FileExplorerPanel(
  cwd: String?,
  open: Boolean,
  selectedPath: String?,
  snackbar: SnackbarStackState,
  onClose: () -> Unit,
  onOpenFile: (path: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val graph = LocalContext.current.appGraph
  val trees = remember { HashMap<String, FileExplorerState>() }
  val state = cwd?.let {
    folderKey(it)
  }?.let { root -> trees.getOrPut(root) { FileExplorerState(root, graph.fileHost) } }
  val showHidden by graph.preferences.showHiddenFiles.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val report = { error: String? -> if (error != null) scope.launch { snackbar.show(error) } }
  LaunchedEffect(state, open) { if (open) state?.refresh() }
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(pipkinColors.sidebar)
      .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
      .imePadding(),
  ) {
    ExplorerHeader(onClose)
    ExplorerToolbar(
      showHidden = showHidden,
      refreshing = state?.refreshing == true,
      enabled = state?.directories?.isNotEmpty() == true,
      onNewFolder = { state?.creatingFolder = true },
      onToggleHidden = { graph.preferences.setShowHiddenFiles(!showHidden) },
      onRefresh = { scope.launch { state?.refresh() } },
    )
    if (state != null) {
      ExplorerTree(
        state = state,
        showHidden = showHidden,
        selectedPath = selectedPath,
        onToggle = { folder -> scope.launch { report(state.toggle(folder)) } },
        onOpenFile = onOpenFile,
        onCreateFolder = { name -> scope.launch { report(state.createFolder(name)) } },
        onRetry = { scope.launch { state.refresh() } },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

/** The explorer's one tab, Files, highlighted as Paseo's active explorer tab is, and the close button. */
@Composable
private fun ExplorerHeader(onClose: () -> Unit) {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .padding(top = PipkinTokens.Spacing.s8)
      .fillMaxWidth()
      .height(56.dp)
      .padding(start = PipkinTokens.Spacing.s8, end = PipkinTokens.Spacing.s12),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(R.string.files_explorer_title),
      modifier = Modifier
        .clip(RoundedCornerShape(PipkinTokens.Radius.md))
        .background(sidebarHighlight())
        .padding(PipkinTokens.Spacing.s8),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
    )
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

/** Paseo's Files toolbar: New folder, the dot-file toggle (highlighted while they're hidden) and Refresh. */
@Composable
private fun ExplorerToolbar(
  showHidden: Boolean,
  refreshing: Boolean,
  enabled: Boolean,
  onNewFolder: () -> Unit,
  onToggleHidden: () -> Unit,
  onRefresh: () -> Unit,
) {
  val colors = pipkinColors
  Row(
    modifier = Modifier.fillMaxWidth().height(36.dp).padding(end = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(1.dp, Alignment.End),
  ) {
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
      onClick = onToggleHidden,
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
