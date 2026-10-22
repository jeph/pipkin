package io.jeph.pipkin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** A pot and [text] in a pill, pinned over other content while something loads. */
@Composable
internal fun LoadingPill(text: String, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .clip(CircleShape)
      .background(pipkinColors.surface2)
      .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    SimmeringPot(size = PipkinTokens.IconSize.sm)
    Text(text = text, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.meta)
  }
}
