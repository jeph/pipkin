package io.jeph.pipkin.ui.git

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.host.GitCommit
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.relativeTime

/**
 * The Commits section, docked under the diff: a header that folds and unfolds it with the number of commits once
 * loaded, and while unfolded the commits ahead of the base, newest first, in a list up to [listMaxHeight] tall that
 * ends by saying so when the host listed only the first of them.
 */
@Composable
internal fun CommitsSection(
  content: CommitsContent,
  collapsed: Boolean,
  listMaxHeight: Dp,
  onToggle: () -> Unit,
  onOpenCommit: (GitCommit) -> Unit,
) {
  val colors = pipkinColors
  val navigationBar = WindowInsets.navigationBars.asPaddingValues()
  Column(modifier = Modifier.fillMaxWidth().padding(bottom = navigationBar.calculateBottomPadding())) {
    HorizontalDivider(thickness = 1.dp, color = colors.border)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onToggle)
        .heightIn(min = 44.dp)
        .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      LucideIcon(
        icon = R.drawable.ic_lucide_chevron_right,
        contentDescription = null,
        modifier = Modifier.rotate(if (collapsed) 0f else 90f),
        size = PipkinTokens.IconSize.sm,
        tint = colors.foregroundMuted,
      )
      Text(
        text = stringResource(R.string.git_commits),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        fontWeight = FontWeight.Bold,
      )
      if (content is CommitsContent.Loaded) CommitCount(content.commits.commits.size)
    }
    if (!collapsed) CommitsContentRows(content, listMaxHeight, onOpenCommit)
  }
}

/** The number of commits as a small accent badge, read out in words. */
@Composable
private fun CommitCount(count: Int) {
  val colors = pipkinColors
  val description = pluralStringResource(R.plurals.git_commits_count, count, count)
  Text(
    text = count.toString(),
    modifier = Modifier
      .semantics { contentDescription = description }
      .background(colors.accentSoft, RoundedCornerShape(9.dp))
      .padding(horizontal = PipkinTokens.Spacing.s6, vertical = PipkinTokens.Spacing.s2),
    color = colors.accent,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    style = LocalTextStyle.current.copy(fontFeatureSettings = PipkinFonts.TABULAR),
    maxLines = 1,
  )
}

@Composable
private fun CommitsContentRows(content: CommitsContent, listMaxHeight: Dp, onOpenCommit: (GitCommit) -> Unit) {
  val colors = pipkinColors
  val notePadding = Modifier.padding(
    start = PipkinTokens.Spacing.s16,
    end = PipkinTokens.Spacing.s16,
    top = PipkinTokens.Spacing.s4,
    bottom = PipkinTokens.Spacing.s12,
  )
  when (content) {
    CommitsContent.Loading -> CommitsNote(
      stringResource(R.string.git_commits_loading),
      colors.foregroundMuted,
      notePadding,
    )

    CommitsContent.Failed -> CommitsNote(stringResource(R.string.git_commits_failed), colors.dotDanger, notePadding)

    is CommitsContent.Loaded -> {
      val commits = content.commits.commits
      if (commits.isEmpty()) {
        val base = branchLabel(content.commits.baseRef) ?: stringResource(R.string.git_base)
        CommitsNote(stringResource(R.string.git_commits_none_ahead, base), colors.foregroundMuted, notePadding)
        return
      }
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = listMaxHeight),
        contentPadding = PaddingValues(bottom = PipkinTokens.Spacing.s8),
      ) {
        itemsIndexed(commits, key = { _, commit -> commit.sha }) { index, commit ->
          CommitRow(commit, isFirst = index == 0, isLast = index == commits.lastIndex, onOpen = {
            onOpenCommit(commit)
          })
        }
        val total = content.commits.total
        if (total > commits.size) {
          item(key = TRUNCATED_KEY) {
            CommitsNote(
              pluralStringResource(R.plurals.git_commits_truncated, total, commits.size, total),
              colors.foregroundMuted,
              notePadding,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CommitsNote(text: String, color: Color, modifier: Modifier) {
  Text(text = text, modifier = modifier, color = color, fontSize = PipkinTokens.FontSize.meta)
}

/** A commit row: the graph node, the short SHA, the subject, how long ago it was written, a chevron. */
@Composable
private fun CommitRow(commit: GitCommit, isFirst: Boolean, isLast: Boolean, onOpen: () -> Unit) {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(IntrinsicSize.Min)
      .padding(horizontal = PipkinTokens.Spacing.s4)
      // Rounded less than the rail is inset, so the clip never bites into the rail where rows meet.
      .clip(RoundedCornerShape(PipkinTokens.Radius.lg))
      .clickable(onClick = onOpen)
      .padding(horizontal = PipkinTokens.Spacing.s12),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    CommitGraphNode(isFirst, isLast)
    Row(
      modifier = Modifier.weight(1f).padding(vertical = PipkinTokens.Spacing.s8),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
    ) {
      Text(
        text = commit.shortSha,
        modifier = Modifier.width(70.dp),
        color = colors.foregroundMuted,
        fontFamily = PipkinFonts.mono,
        fontSize = PipkinTokens.FontSize.sm,
        maxLines = 1,
      )
      Text(
        text = commit.subject,
        modifier = Modifier.weight(1f),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      text = relativeTime(commit.authorTime * 1_000),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      maxLines = 1,
    )
    LucideIcon(
      icon = R.drawable.ic_lucide_chevron_right,
      contentDescription = null,
      size = PipkinTokens.IconSize.sm,
      tint = colors.foregroundExtraMuted,
    )
  }
}

/**
 * The commit graph node: an accent dot on an accent rail that joins it to the rows above and below, starting at the
 * first commit's dot and ending at the last one's.
 */
@Composable
private fun CommitGraphNode(isFirst: Boolean, isLast: Boolean) {
  val accent = pipkinColors.accent
  Canvas(modifier = Modifier.width(GRAPH_MARKER_SIZE).fillMaxHeight()) {
    val center = Offset(size.width / 2, size.height / 2)
    if (!(isFirst && isLast)) {
      drawLine(
        color = accent,
        start = Offset(center.x, if (isFirst) center.y else 0f),
        end = Offset(center.x, if (isLast) center.y else size.height),
        strokeWidth = GRAPH_RAIL_WIDTH.toPx(),
      )
    }
    drawCircle(color = accent, radius = GRAPH_MARKER_SIZE.toPx() / 2, center = center)
  }
}

/** The lazy-list key of the row saying the host listed only the first commits, which can't clash with a SHA. */
private const val TRUNCATED_KEY = "/truncated"

private val GRAPH_MARKER_SIZE = 8.dp
private val GRAPH_RAIL_WIDTH = 2.dp
