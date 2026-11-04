package io.jeph.pipkin.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.git.CommitDiffState
import io.jeph.pipkin.git.CommitTab
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.remote.RemoteConnectDialogs
import io.jeph.pipkin.ui.remote.rememberRemoteConnector
import io.jeph.pipkin.ui.sidebar.rememberFolderNames
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.PaneHeader
import io.jeph.pipkin.ui.workspace.PublishTabActions
import io.jeph.pipkin.ui.workspace.TabAction
import io.jeph.pipkin.workspace.WorkspaceTab
import kotlinx.coroutines.launch

/**
 * One open commit in a pane: the pane's header with the commit's title and the commit's actions, which acts through
 * [controls], then just what the commit changed, every file unfolded, as the Changes tab draws a diff. It follows the
 * global Wrap lines and Hide whitespace settings; hiding whitespace reads the commit again with git's `-w`. A commit
 * never changes, so it's read once per whitespace setting.
 */
@Composable
internal fun CommitScreen(commit: CommitTab, controls: PaneControls, modifier: Modifier = Modifier) {
  val graph = LocalContext.current.appGraph
  val state by commit.state.collectAsStateWithLifecycle()
  val hideWhitespace by graph.preferences.diffHideWhitespace.collectAsStateWithLifecycle()
  val wrapLines by graph.preferences.diffWrapLines.collectAsStateWithLifecycle()
  PublishTabActions(
    controls.shownTab,
    listOf(
      TabAction(R.string.git_commit_close, R.drawable.ic_lucide_x, {
        controls.onCloseTab(WorkspaceTab.Commit(commit))
      }, destructive = true),
    ),
  )
  // A fresh list per commit, so switching commits never carries the old one's scroll position over.
  val list = remember(commit) { LazyListState() }
  LaunchedEffect(commit, hideWhitespace) { commit.load(hideWhitespace) }
  val scope = rememberCoroutineScope()
  val connector = rememberRemoteConnector()
  RemoteConnectDialogs(connector)
  val host = rememberFolderNames().remote(commit.cwd)?.host
  val remoteHosts = LocalContext.current.appGraph.remoteHosts
  val unreachable = (state as? CommitDiffState.Failed)?.remote
  // A read that found the connection gone shows the host disconnected in the sidebar and chat banner too.
  LaunchedEffect(host?.id, unreachable) {
    if (host != null && unreachable != null) remoteHosts.noteFailure(host.id, unreachable.code)
  }
  // A failed read is read again once the host is connected.
  val connect = host?.let { shown ->
    { connector.connect(shown.id, shown.name) { scope.launch { commit.load(hideWhitespace) } } }
  }

  Column(modifier = modifier.fillMaxSize().background(pipkinColors.surface0)) {
    PaneHeader(title = commit.title, cwd = commit.cwd, controls = controls)
    CommitBody(state, wrapLines, list, connect, Modifier.weight(1f))
  }
}

/** What the commit changed, or a note in its place; an error only connecting the remote host fixes offers [onConnect]. */
@Composable
private fun CommitBody(
  state: CommitDiffState,
  wrapLines: Boolean,
  list: LazyListState,
  onConnect: (() -> Unit)?,
  modifier: Modifier,
) {
  when (state) {
    CommitDiffState.Loading -> DiffNote(text = null, modifier = modifier, loading = true)

    CommitDiffState.NotFound -> DiffNote(stringResource(R.string.git_commit_not_found), modifier)

    is CommitDiffState.Failed -> DiffNote(
      state.message.ifEmpty { stringResource(R.string.git_refresh_failed) },
      modifier,
      onConnect = onConnect?.takeIf { state.remote?.needsConnect == true },
    )

    is CommitDiffState.Loaded -> when {
      state.document.tooLarge -> DiffTooLarge(modifier)

      state.document.sections.isEmpty() -> DiffNote(stringResource(R.string.git_empty), modifier)

      else -> DiffView(
        document = state.document,
        wrapLines = wrapLines,
        collapsed = emptySet(),
        onToggle = null,
        state = list,
        modifier = modifier,
        contentPadding = PaddingValues(
          bottom = PipkinTokens.Spacing.s16 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
      )
    }
  }
}

/** A commit tab's actions: close it. */
@Composable
internal fun CommitActionsSheet(onClose: () -> Unit, onDismiss: () -> Unit) {
  PipkinSheet(onDismissRequest = onDismiss) { dismiss ->
    SheetRow(
      label = stringResource(R.string.git_commit_close),
      icon = R.drawable.ic_lucide_x,
      labelColor = pipkinColors.destructive,
      onClick = { dismiss(onClose) },
    )
  }
}
