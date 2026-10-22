package io.jeph.pipkin.ui.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.host.GitChange
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.files.fileIcon
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.text.NumberFormat

/**
 * The top of a file's diff card, over a gap that parts it from the card above: the file's type tile, its name in bold
 * over its folder, muted, each cut off at the end where it doesn't fit; its added and removed line counts; and the icon
 * of how it changed. The card's bottom corners round here too while the file is folded, not [expanded]. A tap calls
 * [onToggle], when given, to fold or unfold the file; a long press does nothing.
 */
@Composable
internal fun FileHeader(section: DiffSection, expanded: Boolean, onToggle: (() -> Unit)?) {
  val colors = pipkinColors
  // Without a long-click handler, releasing a long press would count as a tap and fold the file.
  val clickable = if (onToggle == null) {
    Modifier
  } else {
    Modifier.combinedClickable(onLongClick = {}, hapticFeedbackEnabled = false, onClick = onToggle)
  }
  val shape = if (expanded) CardTopShape else CardShape
  // The page colour behind the card's corners hides the rows scrolling under it while it sticks to the top.
  Box(modifier = Modifier.fillMaxWidth().background(colors.surface0).padding(top = CARD_GAP)) {
    Row(
      modifier = Modifier
        .padding(horizontal = CARD_MARGIN)
        .fillMaxWidth()
        .clip(shape)
        .background(colors.surface1)
        .then(clickable)
        .heightIn(min = 52.dp)
        .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s8),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
    ) {
      FileTile(section.name)
      // Read out as one line, the name then its folder, however the two are stacked on screen.
      val folder = section.folder
      Column(
        modifier = Modifier.weight(1f).clearAndSetSemantics {
          text = AnnotatedString(if (folder.isEmpty()) section.name else "${section.name} $folder")
        },
      ) {
        Text(
          text = section.name,
          color = colors.foreground,
          fontSize = PipkinTokens.FontSize.base,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (section.folder.isNotEmpty()) {
          Text(
            text = section.folder,
            color = colors.foregroundExtraMuted,
            fontSize = PipkinTokens.FontSize.sm,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      DiffStat(section.file.additions, section.file.deletions)
      ChangeIcon(section.file.change)
    }
  }
}

/** The file's type icon, in its own colours, on a tinted tile. */
@Composable
private fun FileTile(name: String) {
  Box(
    modifier = Modifier
      .size(28.dp)
      .background(pipkinColors.infoSoft, RoundedCornerShape(9.dp)),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      painter = painterResource(fileIcon(name)),
      contentDescription = null,
      modifier = Modifier.size(PipkinTokens.IconSize.md),
    )
  }
}

/** A diff stat: `+added -removed`, compact, in green and red code figures. */
@Composable
internal fun DiffStat(additions: Int, deletions: Int) {
  val colors = pipkinColors
  Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4)) {
    StatText("+${compactCount(additions)}", colors.dotSuccess)
    StatText("-${compactCount(deletions)}", colors.destructive)
  }
}

@Composable
private fun StatText(text: String, color: Color) {
  Text(
    text = text,
    color = color,
    fontFamily = PipkinFonts.mono,
    fontSize = PipkinTokens.FontSize.code,
    maxLines = 1,
  )
}

/** [value] as a compact count, `1.2k`, with the app language's digits and unit. */
@Composable
private fun compactCount(value: Int): String {
  val (scaled, unit) = compactScale(value)
  // A compact count reads as one short figure: 2147.5m, not 2,147.5m.
  val number = NumberFormat.getNumberInstance().apply { isGroupingUsed = false }.format(scaled)
  return when (unit) {
    CountUnit.One -> number
    CountUnit.Thousand -> stringResource(R.string.git_count_thousands, number)
    CountUnit.Million -> stringResource(R.string.git_count_millions, number)
  }
}

/** The change icon: a green plus for an added file, a red minus for a deleted one, a dot for any other change. */
@Composable
private fun ChangeIcon(change: GitChange) {
  val colors = pipkinColors
  val (icon, tint, description) = when (change) {
    GitChange.Added -> Triple(R.drawable.ic_lucide_square_plus, colors.dotSuccess, R.string.git_file_added)

    GitChange.Deleted -> Triple(R.drawable.ic_lucide_square_minus, colors.dotDanger, R.string.git_file_deleted)

    GitChange.Modified, GitChange.Renamed, GitChange.Copied ->
      Triple(R.drawable.ic_lucide_square_dot, colors.dotWarning, R.string.git_file_modified)
  }
  LucideIcon(
    icon = icon,
    contentDescription = stringResource(description),
    size = PipkinTokens.IconSize.sm,
    tint = tint,
  )
}
