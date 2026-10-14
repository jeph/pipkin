package io.jeph.pipkin.ui.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.files.Spinner
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** A note in place of a diff, centred: a spinner first while [loading], then [text] if any. */
@Composable
internal fun DiffNote(text: String?, modifier: Modifier = Modifier, loading: Boolean = false) {
  val colors = pipkinColors
  Column(
    modifier = modifier.fillMaxSize().padding(PipkinTokens.Spacing.s24),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
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

/** Paseo's state for a diff too large to send: a file warning over the title and how to narrow it. */
@Composable
internal fun DiffTooLarge(modifier: Modifier = Modifier) {
  val colors = pipkinColors
  Column(
    modifier = modifier.fillMaxSize().padding(
      horizontal = PipkinTokens.Spacing.s24,
      vertical = PipkinTokens.Spacing.s48,
    ),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    LucideIcon(
      icon = R.drawable.ic_lucide_file_warning,
      contentDescription = null,
      size = PipkinTokens.IconSize.lg,
      tint = colors.foregroundMuted,
    )
    Column(
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.git_diff_too_large_title),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        textAlign = TextAlign.Center,
      )
      Text(
        text = stringResource(R.string.git_diff_too_large_description),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
        textAlign = TextAlign.Center,
      )
    }
  }
}
