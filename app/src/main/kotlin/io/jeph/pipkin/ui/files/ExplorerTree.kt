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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.jeph.pipkin.R
import io.jeph.pipkin.host.FileEntry
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

@Composable
internal fun ExplorerTree(
  state: FileExplorerState,
  showHidden: Boolean,
  selectedPath: String?,
  onToggle: (FileEntry) -> Unit,
  onOpenFile: (path: String) -> Unit,
  onCreateFolder: (name: String) -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier,
) {
  val failure = state.failure
  val listed = ROOT_FOLDER in state.directories
  if (failure != null && !listed) {
    TreeFailure(failure, onRetry, modifier)
    return
  }
  if (!listed) {
    CenterNote(modifier) {
      Spinner(PipkinTokens.IconSize.md, contentDescription = null)
      Text(
        stringResource(R.string.files_loading),
        color = pipkinColors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.base,
      )
    }
    return
  }
  val entries = explorerRows(state.directories, state.expanded, showHidden)
  val rows = if (state.creatingFolder) listOf(ExplorerRow.NewFolder) + entries else entries
  if (rows.isEmpty()) {
    CenterNote(modifier) {
      Text(
        text = stringResource(if (showHidden) R.string.files_empty else R.string.files_empty_visible),
        color = pipkinColors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.base,
      )
    }
    return
  }
  val navigationBar = WindowInsets.navigationBars.asPaddingValues()
  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(
      top = PipkinTokens.Spacing.s8,
      bottom = PipkinTokens.Spacing.s16 + navigationBar.calculateBottomPadding(),
    ),
  ) {
    items(rows, key = { row -> if (row is ExplorerRow.Entry) row.entry.path else NEW_FOLDER_KEY }) { row ->
      when (row) {
        is ExplorerRow.NewFolder -> NewFolderRow(
          onCommit = onCreateFolder,
          onCancel = { state.creatingFolder = false },
        )

        is ExplorerRow.Entry -> EntryRow(
          entry = row.entry,
          depth = row.depth,
          expanded = row.entry.path in state.expanded,
          loading = row.entry.path in state.loading,
          selected = row.entry.path == selectedPath,
          onClick = { if (row.entry.isDirectory) onToggle(row.entry) else onOpenFile(row.entry.path) },
        )
      }
    }
  }
}

/** The lazy-list key of the new folder's name field, which can't clash with a path. */
private const val NEW_FOLDER_KEY = "/new-folder"

/** Paseo's tree row: 12dp in plus 12dp a level, a 16dp chevron or file icon, and the name at 76% strength. */
@Composable
private fun EntryRow(
  entry: FileEntry,
  depth: Int,
  expanded: Boolean,
  loading: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
) {
  TreeRow(
    depth,
    modifier = Modifier.background(
      if (selected) sidebarHighlight() else Color.Transparent,
    ).clickable(onClick = onClick),
  ) {
    when {
      !entry.isDirectory -> Image(
        painter = painterResource(fileIcon(entry.name)),
        contentDescription = null,
        modifier = Modifier.size(PipkinTokens.IconSize.md),
      )

      loading -> Spinner(PipkinTokens.IconSize.sm, contentDescription = null)

      else -> Chevron(expanded)
    }
    Text(
      text = entry.name,
      modifier = Modifier.weight(1f),
      color = pipkinColors.foreground.copy(alpha = NAME_ALPHA),
      fontSize = PipkinTokens.FontSize.base,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * Paseo's draft row for a new folder: a closed folder's chevron and a name field that takes focus. Done or leaving the
 * field commits a name; an empty one cancels.
 */
@Composable
private fun NewFolderRow(onCommit: (String) -> Unit, onCancel: () -> Unit) {
  val colors = pipkinColors
  var name by remember { mutableStateOf("") }
  var settled by remember { mutableStateOf(false) }
  var focused by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val commit = {
    if (!settled) {
      settled = true
      val trimmed = name.trim()
      if (trimmed.isEmpty()) onCancel() else onCommit(trimmed)
    }
  }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  TreeRow(depth = 0) {
    Chevron(expanded = false)
    BasicTextField(
      value = name,
      onValueChange = { name = it },
      modifier = Modifier
        .weight(1f)
        .focusRequester(focusRequester)
        .onFocusChanged { state ->
          if (focused && !state.isFocused) commit()
          focused = state.isFocused
        },
      singleLine = true,
      textStyle = TextStyle(color = colors.foreground, fontSize = PipkinTokens.FontSize.base),
      cursorBrush = SolidColor(colors.accent),
      keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        imeAction = ImeAction.Done,
      ),
      keyboardActions = KeyboardActions(onDone = { commit() }),
      decorationBox = { field ->
        Box {
          if (name.isEmpty()) {
            Text(
              text = stringResource(R.string.files_folder_placeholder),
              color = colors.foregroundExtraMuted,
              fontSize = PipkinTokens.FontSize.base,
            )
          }
          field()
        }
      },
    )
  }
}

@Composable
private fun TreeRow(depth: Int, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(
        start = PipkinTokens.Spacing.s12 + INDENT_PER_LEVEL * depth,
        end = PipkinTokens.Spacing.s8,
        top = PipkinTokens.Spacing.s6,
        bottom = PipkinTokens.Spacing.s6,
      ),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    content()
  }
}

/** A folder's chevron, pointing right and turning down once the folder is open. */
@Composable
private fun Chevron(expanded: Boolean) {
  LucideIcon(
    icon = R.drawable.ic_lucide_chevron_right,
    contentDescription = null,
    modifier = Modifier.rotate(if (expanded) 90f else 0f),
    tint = pipkinColors.foregroundExtraMuted,
  )
}

/** Paseo's loading spinner in a 16dp icon slot; [contentDescription] names it when nothing else says what loads. */
@Composable
internal fun Spinner(size: Dp, contentDescription: String?) {
  Box(
    modifier = Modifier
      .size(PipkinTokens.IconSize.md)
      .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator(
      modifier = Modifier.size(size),
      color = pipkinColors.foregroundMuted,
      strokeWidth = 1.5.dp,
    )
  }
}

/** Why the top folder couldn't be listed, and Retry. */
@Composable
private fun TreeFailure(message: String, onRetry: () -> Unit, modifier: Modifier) {
  val colors = pipkinColors
  CenterNote(modifier) {
    Text(
      text = message,
      color = colors.destructive,
      fontSize = PipkinTokens.FontSize.base,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stringResource(R.string.files_retry),
      modifier = Modifier
        .clip(CircleShape)
        .border(1.dp, colors.border, CircleShape)
        .clickable(onClick = onRetry)
        .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s4),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
    )
  }
}

@Composable
private fun CenterNote(modifier: Modifier, content: @Composable () -> Unit) {
  Column(
    modifier = modifier.fillMaxSize().padding(PipkinTokens.Spacing.s16),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    content()
  }
}

/** The sidebar's highlight: the light sidebar is surface2 itself, so its highlight steps up one surface. */
@Composable
internal fun sidebarHighlight(): Color = pipkinColors.let { if (it.isDark) it.surface2 else it.surface3 }

/** Paseo's tree names sit at 76% strength, so the file icons' colours lead. */
private const val NAME_ALPHA = 0.76f

private val INDENT_PER_LEVEL = 12.dp
