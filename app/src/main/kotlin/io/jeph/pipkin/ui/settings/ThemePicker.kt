package io.jeph.pipkin.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.EvenRows
import io.jeph.pipkin.ui.theme.AppTheme
import io.jeph.pipkin.ui.theme.PipkinColors
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.PorcelainColors
import io.jeph.pipkin.ui.theme.StoneColors
import io.jeph.pipkin.ui.theme.pipkinColors

/** The Theme setting: the themes in even rows, each a small chat drawn in its colours over its name. */
@Composable
internal fun ThemePicker(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(PipkinTokens.Spacing.s16),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    Text(
      text = stringResource(R.string.settings_theme),
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.base,
      fontWeight = FontWeight.Medium,
    )
    // As many to a row as fit their names, so a large font gives two rows rather than cut-off names.
    EvenRows(gap = PipkinTokens.Spacing.s8, modifier = Modifier.fillMaxWidth().selectableGroup()) {
      AppTheme.entries.forEach { theme ->
        ThemeOption(theme, theme == selected, onClick = { onSelect(theme) })
      }
    }
  }
}

@Composable
private fun ThemeOption(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
  val colors = pipkinColors
  Column(
    modifier = Modifier
      .clip(SHAPE)
      .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
      .padding(vertical = PipkinTokens.Spacing.s4),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(SWATCH_HEIGHT)
        .clip(SHAPE)
        .border(
          width = if (selected) 2.dp else 1.dp,
          color = if (selected) colors.accent else colors.border,
          shape = SHAPE,
        ),
    ) {
      if (theme != AppTheme.System) {
        drawPreview(theme.colors(systemDark = false))
        return@Canvas
      }
      // System is Porcelain by day and Stone by night, so it shows half of each.
      clipRect(right = size.width / 2) { drawPreview(PorcelainColors) }
      clipRect(left = size.width / 2) { drawPreview(StoneColors) }
    }
    Text(
      text = stringResource(theme.label),
      color = if (selected) colors.foreground else colors.foregroundMuted,
      fontSize = 12.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** A tiny chat in [palette]: a prompt card, two lines of reply and the send button. */
private fun DrawScope.drawPreview(palette: PipkinColors) {
  val corner = CornerRadius(3.dp.toPx())
  val line = 3.dp.toPx()
  drawRect(palette.surface0)
  drawRoundRect(
    color = palette.surface2,
    topLeft = Offset(size.width * 0.14f, size.height * 0.16f),
    size = Size(size.width * 0.72f, size.height * 0.26f),
    cornerRadius = corner,
  )
  drawRoundRect(
    color = palette.foregroundMuted,
    topLeft = Offset(size.width * 0.14f, size.height * 0.54f),
    size = Size(size.width * 0.62f, line),
    cornerRadius = corner,
  )
  drawRoundRect(
    color = palette.foregroundMuted,
    topLeft = Offset(size.width * 0.14f, size.height * 0.54f + line * 2),
    size = Size(size.width * 0.4f, line),
    cornerRadius = corner,
  )
  drawCircle(
    color = palette.accent,
    radius = 5.dp.toPx(),
    center = Offset(size.width * 0.78f, size.height * 0.8f),
  )
}

private val AppTheme.label: Int
  @StringRes get() = when (this) {
    AppTheme.System -> R.string.settings_theme_system
    AppTheme.Porcelain -> R.string.settings_theme_porcelain
    AppTheme.Clay -> R.string.settings_theme_clay
    AppTheme.Cobalt -> R.string.settings_theme_cobalt
    AppTheme.Stone -> R.string.settings_theme_stone
  }

private val SHAPE = RoundedCornerShape(12.dp)
private val SWATCH_HEIGHT = 56.dp
