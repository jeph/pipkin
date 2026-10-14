package io.jeph.pipkin.ui.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.GitCommit
import io.jeph.pipkin.host.GitDiffMode
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.files.Spinner
import io.jeph.pipkin.ui.onboarding.PrimaryButton
import io.jeph.pipkin.ui.onboarding.SecondaryButton
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/**
 * The Explorer's Changes tab for the trusted [workspace], Paseo's Changes pane: the comparison selector, its totals,
 * Refresh and the diff options, the diff as one document, and the Commits section docked under it. Nothing polls: the
 * diff, and the commits while unfolded, load when the tab shows while the Explorer is [open] (the Explorer opening,
 * switching to this tab or to this workspace), when the comparison or Hide whitespace changes, and on Refresh; the
 * commits also when unfolded. Refresh also runs [onDetect] and says on [snackbar] when it failed; tapping a commit
 * calls [onOpenCommit].
 */
@Composable
internal fun ChangesPane(
  workspace: GitWorkspace,
  open: Boolean,
  snackbar: SnackbarStackState,
  onDetect: suspend () -> Unit,
  onOpenCommit: (GitCommit) -> Unit,
  modifier: Modifier = Modifier,
) {
  val preferences = LocalContext.current.appGraph.preferences
  val modes by preferences.diffModes.collectAsStateWithLifecycle()
  val hideWhitespace by preferences.diffHideWhitespace.collectAsStateWithLifecycle()
  val wrapLines by preferences.diffWrapLines.collectAsStateWithLifecycle()
  val commitsCollapsed by preferences.commitsCollapsed.collectAsStateWithLifecycle()
  val chosenMode = modes[folderKey(workspace.root)]
  LaunchedEffect(workspace, open, chosenMode, hideWhitespace) {
    if (open) workspace.refreshChanges(chosenMode, hideWhitespace)
  }
  // The diff's triggers reload the commits too, so the two never disagree about what HEAD is.
  LaunchedEffect(workspace, open, chosenMode, hideWhitespace, commitsCollapsed) {
    if (open && !commitsCollapsed) workspace.refreshCommits()
  }
  val scope = rememberCoroutineScope()
  var refreshing by remember(workspace) { mutableStateOf(false) }
  val refreshFailed = stringResource(R.string.git_refresh_failed)
  val refresh: () -> Unit = {
    scope.launch {
      refreshing = true
      onDetect()
      val changed = workspace.refreshChanges(chosenMode, hideWhitespace)
      val succeeded = changed && (commitsCollapsed || workspace.refreshCommits())
      refreshing = false
      if (!succeeded) snackbar.show(refreshFailed)
    }
  }
  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val commitsMaxHeight = maxHeight / 2
    Column(modifier = Modifier.fillMaxSize()) {
      ChangesToolbar(
        workspace = workspace,
        mode = chosenMode ?: workspace.defaultMode ?: GitDiffMode.Uncommitted,
        refreshing = refreshing || workspace.reloading || workspace.commitsReloading,
        onSelectMode = { preferences.setDiffMode(workspace.root, it) },
        onRefresh = refresh,
      )
      ChangesBody(workspace, wrapLines, Modifier.weight(1f))
      CommitsSection(
        content = workspace.commits,
        collapsed = commitsCollapsed,
        listMaxHeight = commitsMaxHeight,
        onToggle = { preferences.setCommitsCollapsed(!commitsCollapsed) },
        onOpenCommit = onOpenCommit,
      )
    }
  }
}

/**
 * The Changes tab of a repository the user hasn't trusted: why git needs their trust before it runs there, then
 * Trust this repository and Not now. Nothing loads until it's trusted.
 */
@Composable
internal fun TrustPrompt(onTrust: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  Column(
    modifier = modifier.fillMaxSize().padding(PipkinTokens.Spacing.s24),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = stringResource(R.string.git_trust_title),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.lg,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stringResource(R.string.git_trust_text),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(PipkinTokens.Spacing.s4))
    PrimaryButton(text = stringResource(R.string.git_trust), onClick = onTrust, modifier = Modifier.fillMaxWidth())
    SecondaryButton(text = stringResource(R.string.git_not_now), onClick = onNotNow, modifier = Modifier.fillMaxWidth())
  }
}

/**
 * Paseo's Changes toolbar: the comparison [mode], which a tap offers to change, the diff's totals once one has loaded,
 * then Refresh, a spinner while [refreshing], and the diff options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangesToolbar(
  workspace: GitWorkspace,
  mode: GitDiffMode,
  refreshing: Boolean,
  onSelectMode: (GitDiffMode) -> Unit,
  onRefresh: () -> Unit,
) {
  val colors = pipkinColors
  var modesOpen by remember { mutableStateOf(false) }
  var optionsOpen by remember { mutableStateOf(false) }
  val loaded = workspace.changes as? ChangesContent.Loaded
  Row(
    modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    ModeTrigger(mode, onClick = { modesOpen = true })
    if (loaded != null) DiffStat(loaded.document.additions, loaded.document.deletions)
    Spacer(modifier = Modifier.weight(1f))
    if (refreshing) {
      Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Spinner(PipkinTokens.IconSize.md, stringResource(R.string.git_refreshing))
      }
    } else {
      IconButton28(
        icon = R.drawable.ic_lucide_refresh_cw,
        contentDescription = stringResource(R.string.git_refresh),
        onClick = onRefresh,
        tint = colors.foregroundExtraMuted,
      )
    }
    IconButton28(
      icon = R.drawable.ic_lucide_ellipsis,
      contentDescription = stringResource(R.string.git_options),
      onClick = { optionsOpen = true },
      tint = colors.foregroundExtraMuted,
    )
  }
  HorizontalDivider(thickness = 1.dp, color = colors.border)
  if (modesOpen) {
    val changes = loaded?.changes
    ModeSheet(
      mode = mode,
      committedDescription = committedDescription(changes?.branch, changes?.baseRef),
      onSelect = onSelectMode,
      onDismiss = { modesOpen = false },
    )
  }
  if (optionsOpen) OptionsSheet(workspace, onDismiss = { optionsOpen = false })
}

/** Paseo's compact selector trigger: the comparison's name and a small chevron, in a pill. */
@Composable
private fun ModeTrigger(mode: GitDiffMode, onClick: () -> Unit) {
  val colors = pipkinColors
  val description = stringResource(R.string.git_diff_mode)
  Row(
    modifier = Modifier
      .height(24.dp)
      .clip(RoundedCornerShape(PipkinTokens.Radius.base))
      .clickable(onClick = onClick)
      .semantics { contentDescription = description }
      .padding(horizontal = PipkinTokens.Spacing.s4),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    Text(text = modeLabel(mode), color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm, maxLines = 1)
    LucideIcon(
      icon = R.drawable.ic_lucide_chevron_down,
      contentDescription = null,
      size = PipkinTokens.IconSize.xs,
      tint = colors.foregroundExtraMuted,
    )
  }
}

@Composable
private fun modeLabel(mode: GitDiffMode): String = stringResource(
  when (mode) {
    GitDiffMode.Uncommitted -> R.string.git_uncommitted
    GitDiffMode.Base -> R.string.git_committed
  },
)

/** The comparisons, the selected one checked; Committed says which branch it compares with which. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSheet(
  mode: GitDiffMode,
  committedDescription: String?,
  onSelect: (GitDiffMode) -> Unit,
  onDismiss: () -> Unit,
) {
  PipkinSheet(onDismissRequest = onDismiss) {
    for (option in GitDiffMode.entries) {
      MenuRow(
        label = modeLabel(option),
        description = committedDescription.takeIf { option == GitDiffMode.Base },
        icon = null,
        checked = option == mode,
        onClick = {
          onDismiss()
          onSelect(option)
        },
      )
    }
  }
}

/**
 * Paseo's diff options: fold or unfold every file, then the whitespace and wrapping toggles, each named for what a
 * tap switches to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(workspace: GitWorkspace, onDismiss: () -> Unit) {
  val preferences = LocalContext.current.appGraph.preferences
  val hideWhitespace by preferences.diffHideWhitespace.collectAsStateWithLifecycle()
  val wrapLines by preferences.diffWrapLines.collectAsStateWithLifecycle()
  val paths = (workspace.changes as? ChangesContent.Loaded)?.document?.sections.orEmpty().map { it.file.path }
  val allCollapsed = paths.isNotEmpty() && workspace.collapsed.containsAll(paths)
  PipkinSheet(onDismissRequest = onDismiss) {
    MenuRow(
      label = stringResource(if (allCollapsed) R.string.git_expand_all else R.string.git_collapse_all),
      description = null,
      icon = if (allCollapsed) {
        R.drawable.ic_lucide_list_chevrons_up_down
      } else {
        R.drawable.ic_lucide_list_chevrons_down_up
      },
      checked = false,
      onClick = {
        onDismiss()
        workspace.collapsed = if (allCollapsed) emptySet() else paths.toSet()
      },
    )
    HorizontalDivider(thickness = 1.dp, color = pipkinColors.border)
    MenuRow(
      label = stringResource(if (hideWhitespace) R.string.git_show_whitespace else R.string.git_hide_whitespace),
      description = null,
      icon = R.drawable.ic_lucide_pilcrow,
      checked = false,
      onClick = {
        onDismiss()
        preferences.setDiffHideWhitespace(!hideWhitespace)
      },
    )
    MenuRow(
      label = stringResource(if (wrapLines) R.string.git_scroll_long_lines else R.string.git_wrap_long_lines),
      description = null,
      icon = R.drawable.ic_lucide_wrap_text,
      checked = false,
      onClick = {
        onDismiss()
        preferences.setDiffWrapLines(!wrapLines)
      },
    )
  }
}

/** A sheet menu item: its icon, its label over an optional [description], and a check while [checked]. */
@Composable
private fun MenuRow(label: String, description: String?, icon: Int?, checked: Boolean, onClick: () -> Unit) {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 44.dp)
      .clickable(onClick = onClick)
      .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s6),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    if (icon != null) LucideIcon(icon = icon, contentDescription = null, tint = colors.foregroundMuted)
    Column(modifier = Modifier.weight(1f)) {
      Text(text = label, color = colors.foreground, fontSize = PipkinTokens.FontSize.base)
      if (description != null) {
        Text(
          text = description,
          color = colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.sm,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (checked) LucideIcon(icon = R.drawable.ic_lucide_check, contentDescription = null, tint = colors.foreground)
  }
}

/**
 * The diff, or in its place Paseo's notes: checking the repository during the first load, git's error, the diff being
 * too large, or no changes. A refresh keeps what it replaces until its reply arrives.
 */
@Composable
private fun ChangesBody(workspace: GitWorkspace, wrapLines: Boolean, modifier: Modifier) {
  when (val content = workspace.changes) {
    ChangesContent.Loading -> DiffNote(stringResource(R.string.git_checking_repository), modifier, loading = true)

    is ChangesContent.Failed -> DiffNote(
      content.message.ifEmpty {
        stringResource(R.string.git_refresh_failed)
      },
      modifier,
    )

    is ChangesContent.Loaded -> when {
      content.document.tooLarge -> DiffTooLarge(modifier)

      content.document.sections.isEmpty() -> DiffNote(stringResource(R.string.git_empty), modifier)

      else -> DiffView(
        document = content.document,
        wrapLines = wrapLines,
        collapsed = workspace.collapsed,
        onToggle = { path ->
          workspace.collapsed =
            if (path in workspace.collapsed) workspace.collapsed - path else workspace.collapsed + path
        },
        state = workspace.list,
        modifier = modifier,
      )
    }
  }
}
