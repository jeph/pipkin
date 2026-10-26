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
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.PaneHeader
import io.jeph.pipkin.ui.workspace.PublishTabActions
import io.jeph.pipkin.ui.workspace.TabAction
import io.jeph.pipkin.workspace.WorkspaceTab

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

  Column(modifier = modifier.fillMaxSize().background(pipkinColors.surface0)) {
    PaneHeader(title = commit.title, cwd = commit.cwd, controls = controls)
    val body = Modifier.weight(1f)
    when (val shown = state) {
      CommitDiffState.Loading -> DiffNote(text = null, modifier = body, loading = true)

      CommitDiffState.NotFound -> DiffNote(stringResource(R.string.git_commit_not_found), body)

      is CommitDiffState.Failed -> DiffNote(shown.message.ifEmpty { stringResource(R.string.git_refresh_failed) }, body)

      is CommitDiffState.Loaded -> when {
        shown.document.tooLarge -> DiffTooLarge(body)

        shown.document.sections.isEmpty() -> DiffNote(stringResource(R.string.git_empty), body)

        else -> DiffView(
          document = shown.document,
          wrapLines = wrapLines,
          collapsed = emptySet(),
          onToggle = null,
          state = list,
          modifier = body,
          contentPadding = PaddingValues(
            bottom = PipkinTokens.Spacing.s16 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
          ),
        )
      }
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
