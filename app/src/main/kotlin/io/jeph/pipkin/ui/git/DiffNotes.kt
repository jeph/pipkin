package io.jeph.pipkin.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.files.Spinner
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** A note in place of a diff, centred on a card: a spinner first while [loading], then [text] if any. */
@Composable
internal fun DiffNote(text: String?, modifier: Modifier = Modifier, loading: Boolean = false) {
  val colors = pipkinColors
  NoteCard(modifier, Alignment.Center, PipkinTokens.Spacing.s8) {
    if (loading) Spinner(PipkinTokens.IconSize.md, contentDescription = null)
    if (text != null) {
      Text(
        text = text,
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.base,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/** A diff too large to send, on a card: a file warning on its tile over the title and how to narrow it. */
@Composable
internal fun DiffTooLarge(modifier: Modifier = Modifier) {
  val colors = pipkinColors
  NoteCard(modifier, Alignment.TopCenter, PipkinTokens.Spacing.s12) {
    Box(
      modifier = Modifier.size(36.dp).background(colors.warningSoft, RoundedCornerShape(PipkinTokens.Radius.md)),
      contentAlignment = Alignment.Center,
    ) {
      LucideIcon(
        icon = R.drawable.ic_lucide_file_warning,
        contentDescription = null,
        size = PipkinTokens.IconSize.lg,
        tint = colors.dotWarning,
      )
    }
    Column(
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.git_diff_too_large_title),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.content,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
      )
      Text(
        text = stringResource(R.string.git_diff_too_large_description),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.meta,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/** A diff card holding a note's [content] centred, the card placed at [alignment] in the space a diff would fill. */
@Composable
private fun NoteCard(
  modifier: Modifier,
  alignment: Alignment,
  spacing: Dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  Box(
    modifier = modifier.fillMaxSize().padding(horizontal = CARD_MARGIN, vertical = PipkinTokens.Spacing.s24),
    contentAlignment = alignment,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(pipkinColors.surface1, CardShape)
        .padding(PipkinTokens.Spacing.s24),
      verticalArrangement = Arrangement.spacedBy(spacing),
      horizontalAlignment = Alignment.CenterHorizontally,
      content = content,
    )
  }
}
