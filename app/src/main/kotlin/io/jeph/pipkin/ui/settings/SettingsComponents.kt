package io.jeph.pipkin.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Paseo settings section: an optional 12sp muted title over a bordered surface1 card. */
@Composable
internal fun SettingsSection(
  title: String?,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.lg)
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    if (title != null) {
      Text(
        text = title,
        modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s4),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
      )
    }
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(shape)
          .background(colors.surface1)
          .border(1.dp, colors.border, shape),
      content = content,
    )
  }
}

@Composable
internal fun SettingsDivider() {
  HorizontalDivider(thickness = 1.dp, color = pipkinColors.border)
}

/** A 56dp settings card row: optional leading icon, title, optional muted subtitle, trailing control. */
@Composable
internal fun SettingsRow(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  titleColor: Color = pipkinColors.foreground,
  enabled: Boolean = true,
  onClick: (() -> Unit)? = null,
  leading: (@Composable () -> Unit)? = null,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  val colors = pipkinColors
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .alpha(if (enabled) 1f else 0.5f)
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    if (leading != null) {
      Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) { leading() }
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, color = titleColor, fontSize = PipkinTokens.FontSize.base)
      if (subtitle != null) {
        Text(
          text = subtitle,
          color = colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.sm,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    trailing()
  }
}

/** Muted trailing value of a [SettingsRow]. */
@Composable
internal fun SettingsValue(text: String) {
  Text(text = text, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.base, maxLines = 1)
}

/** A 12sp line under a section or field; destructive for errors. */
@Composable
internal fun SettingsCaption(text: String, modifier: Modifier = Modifier, color: Color = pipkinColors.foregroundMuted) {
  Text(
    text = text,
    modifier = modifier.padding(horizontal = PipkinTokens.Spacing.s4),
    color = color,
    fontSize = PipkinTokens.FontSize.sm,
  )
}

/** Paseo's segmented control: a surface2 track with the selected segment raised on surface3. */
@Composable
internal fun <T> SegmentedControl(
  options: List<T>,
  selected: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  segment: @Composable (option: T, isSelected: Boolean) -> Unit,
) {
  val colors = pipkinColors
  Row(
    modifier =
      modifier
        .clip(RoundedCornerShape(PipkinTokens.Radius.md))
        .background(colors.surface2)
        .padding(PipkinTokens.Spacing.s2),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s2),
  ) {
    options.forEach { option ->
      val isSelected = option == selected
      Box(
        modifier =
          Modifier
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(PipkinTokens.Radius.base))
            .background(if (isSelected) colors.surface3 else Color.Transparent)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
            .padding(horizontal = PipkinTokens.Spacing.s8),
        contentAlignment = Alignment.Center,
      ) {
        segment(option, isSelected)
      }
    }
  }
}

/** Paseo's dashed "add" row below a card. */
@Composable
internal fun DashedActionRow(
  label: String,
  @DrawableRes icon: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = pipkinColors
  val radius = PipkinTokens.Radius.lg
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .drawBehind {
          val stroke = 1.dp.toPx()
          val dash = 4.dp.toPx()
          drawRoundRect(
            color = colors.borderAccent,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
          )
        }
        .clip(RoundedCornerShape(radius))
        .clickable(onClick = onClick)
        .padding(horizontal = PipkinTokens.Spacing.s16),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    LucideIcon(icon = icon, contentDescription = null, tint = colors.foregroundMuted)
    Text(text = label, color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.base)
  }
}

/** Paseo's small outline button for row actions. */
@Composable
internal fun SmallButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.xxl)
  Box(
    modifier =
      modifier
        .heightIn(min = 28.dp)
        .clip(shape)
        .background(colors.surface2)
        .border(1.dp, colors.borderAccent, shape)
        .alpha(if (enabled) 1f else 0.5f)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = PipkinTokens.Spacing.s12),
    contentAlignment = Alignment.Center,
  ) {
    Text(text = label, color = colors.foreground, fontSize = PipkinTokens.FontSize.sm)
  }
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
  context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}
