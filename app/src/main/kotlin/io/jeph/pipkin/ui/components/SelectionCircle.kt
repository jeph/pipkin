package io.jeph.pipkin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** A selected row's filled accent check, or an empty ring while it isn't selected. */
@Composable
internal fun SelectionCircle(selected: Boolean) {
  val colors = pipkinColors
  if (selected) {
    Box(
      modifier = Modifier.size(PipkinTokens.IconSize.lg).clip(CircleShape).background(colors.accent),
      contentAlignment = Alignment.Center,
    ) {
      LucideIcon(
        icon = R.drawable.ic_lucide_check,
        contentDescription = null,
        size = PipkinTokens.IconSize.xs,
        tint = colors.accentForeground,
      )
    }
  } else {
    LucideIcon(
      icon = R.drawable.ic_lucide_circle,
      contentDescription = null,
      size = PipkinTokens.IconSize.lg,
      tint = colors.foregroundExtraMuted,
    )
  }
}
