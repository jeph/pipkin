package io.jeph.pipkin.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.jeph.pipkin.ui.chat.MenuButton
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.files.ExplorerToggle
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.TabCountButton
import io.jeph.pipkin.ui.workspace.rememberWorkspaceTabs

/**
 * One open commit, shown in place of [chat]: the workspace header with the commit's title, the workspace's tab
 * button, the commit's actions and the explorer toggle, then just what the commit changed, every file unfolded, as
 * the Changes tab draws a diff. It follows the global Wrap lines and Hide whitespace settings; hiding whitespace reads
 * the commit again with git's `-w`. A commit never changes, so it's read once per whitespace setting.
 */
@Composable
internal fun CommitScreen(
  commit: CommitTab,
  chat: AgentProcess,
  onToggleSidebar: () -> Unit,
  onOpenTabs: () -> Unit,
  onToggleExplorer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val graph = LocalContext.current.appGraph
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val tabs = rememberWorkspaceTabs(chat)
  val state by commit.state.collectAsStateWithLifecycle()
  val hideWhitespace by graph.preferences.diffHideWhitespace.collectAsStateWithLifecycle()
  val wrapLines by graph.preferences.diffWrapLines.collectAsStateWithLifecycle()
  var actionsOpen by remember(commit) { mutableStateOf(false) }
  // A fresh list per commit, so switching commits never carries the old one's scroll position over.
  val list = remember(commit) { LazyListState() }
  LaunchedEffect(commit, hideWhitespace) { commit.load(hideWhitespace) }

  Column(modifier = modifier.fillMaxSize().background(pipkinColors.surface0)) {
    HeaderBar(
      title = commit.title,
      subtitle = hello?.home?.let { tildePath(commit.cwd, it) } ?: commit.cwd,
      leading = { MenuButton(onToggleSidebar) },
      trailing = {
        TabCountButton(count = tabs.count, onClick = onOpenTabs)
        IconButton28(
          icon = R.drawable.ic_lucide_ellipsis,
          contentDescription = stringResource(R.string.git_commit_actions),
          onClick = { actionsOpen = true },
        )
        ExplorerToggle(onToggleExplorer)
      },
    )
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

  if (actionsOpen) {
    CommitActionsSheet(onClose = { graph.commits.close(commit) }, onDismiss = { actionsOpen = false })
  }
}

/** A commit tab's actions: close it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommitActionsSheet(onClose: () -> Unit, onDismiss: () -> Unit) {
  PipkinSheet(onDismissRequest = onDismiss) {
    SheetRow(
      label = stringResource(R.string.git_commit_close),
      icon = R.drawable.ic_lucide_x,
      labelColor = pipkinColors.destructive,
      onClick = {
        onDismiss()
        onClose()
      },
    )
  }
}
