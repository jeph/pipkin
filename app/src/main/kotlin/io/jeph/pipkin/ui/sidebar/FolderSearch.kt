package io.jeph.pipkin.ui.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.Spinner
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** What the browse host answered for [query]: matching folders, or why the search failed. */
internal data class FolderSearch(val query: String, val paths: List<String>, val failure: String?)

/**
 * Search results in place of the folder browser: [found] when it answers [query], else a spinner while
 * that search runs. Tapping a folder hands it to [onChoose]; folders [isWorkspace] accepts are greyed.
 */
@Composable
internal fun FolderSearchResults(
  query: String,
  found: FolderSearch?,
  home: String,
  isWorkspace: (cwd: String) -> Boolean,
  modifier: Modifier = Modifier,
  onChoose: (String) -> Unit,
) {
  val colors = pipkinColors
  LazyColumn(modifier = modifier.fillMaxWidth()) {
    when {
      found == null || found.query != query -> item { Searching() }

      found.failure != null -> item { Note(found.failure, colors.destructive) }

      found.paths.isEmpty() -> item { Note(stringResource(R.string.shell_browse_no_matches), colors.foregroundMuted) }

      else -> items(found.paths, key = { it }) { path ->
        FolderResultRow(path, home, added = isWorkspace(path), onClick = { onChoose(path) })
      }
    }
  }
}

/** The folder's name over its full path, shortened in the middle so both ends stay readable. */
@Composable
private fun FolderResultRow(path: String, home: String, added: Boolean, onClick: () -> Unit) {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 52.dp)
      .alpha(if (added) 0.5f else 1f)
      .clickable(enabled = !added, onClick = onClick)
      .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s6),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    LucideIcon(icon = R.drawable.ic_lucide_folder, contentDescription = null)
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = projectName(path, home),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = tildePath(path, home),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
      )
    }
    if (added) {
      Text(
        text = stringResource(R.string.shell_browse_added_badge),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
      )
    }
  }
}

@Composable
private fun Searching() {
  Row(
    modifier = Modifier.fillMaxWidth().padding(
      horizontal = PipkinTokens.Spacing.s16,
      vertical = PipkinTokens.Spacing.s12,
    ),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Spinner()
    Text(
      text = stringResource(R.string.shell_browse_searching),
      color = pipkinColors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
    )
  }
}
