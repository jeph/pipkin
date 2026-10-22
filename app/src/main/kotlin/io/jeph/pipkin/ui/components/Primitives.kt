package io.jeph.pipkin.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

@Composable
fun LucideIcon(
  @DrawableRes icon: Int,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  size: Dp = PipkinTokens.IconSize.md,
  tint: Color = pipkinColors.foreground,
) {
  Icon(
    painter = painterResource(icon),
    contentDescription = contentDescription,
    modifier = modifier.size(size),
    tint = tint,
  )
}

/** A circular icon button, 28dp unless [modifier] sets a size, which comes first and so wins. */
@Composable
fun RoundIconButton(
  @DrawableRes icon: Int,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  background: Color = Color.Transparent,
  tint: Color = pipkinColors.foreground,
  iconSize: Dp = PipkinTokens.IconSize.md,
) {
  Box(
    modifier = modifier.roundButton(size = 28.dp, background = background, enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    LucideIcon(icon = icon, contentDescription = contentDescription, size = iconSize, tint = tint)
  }
}

/** A [size] circle filled with [background] that calls [onClick], dimmed while not [enabled]. */
internal fun Modifier.roundButton(size: Dp, background: Color, enabled: Boolean, onClick: () -> Unit): Modifier =
  alpha(if (enabled) 1f else 0.5f)
    .size(size)
    .clip(CircleShape)
    .background(background)
    .clickable(enabled = enabled, onClick = onClick)

/**
 * A muted one-line caption that, when it doesn't fit, pans to its end and around again every few seconds, so all of it
 * shows in turn. Text that fits stays still.
 */
@Composable
fun PanningText(text: AnnotatedString, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier.basicMarquee(
      iterations = Int.MAX_VALUE,
      initialDelayMillis = PAN_DELAY_MILLIS,
      repeatDelayMillis = PAN_DELAY_MILLIS,
    ),
    color = pipkinColors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.sm,
    maxLines = 1,
    softWrap = false,
  )
}

private const val PAN_DELAY_MILLIS = 2_500

/** Paseo's bottom-sheet row: 44dp minimum, 20dp icon slot, value capped at 45% width. */
@Composable
fun SheetRow(
  label: String,
  modifier: Modifier = Modifier,
  @DrawableRes icon: Int? = null,
  value: String? = null,
  labelColor: Color = pipkinColors.foreground,
  enabled: Boolean = true,
  onClick: (() -> Unit)? = null,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  val colors = pipkinColors
  BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val valueMaxWidth = maxWidth * 0.45f
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(min = 44.dp)
          .alpha(if (enabled) 1f else 0.5f)
          .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
          .padding(horizontal = PipkinTokens.Spacing.s16),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
    ) {
      SheetRowContent(icon, label, labelColor, value, valueMaxWidth, colors.foregroundMuted, trailing)
    }
  }
}

@Composable
private fun RowScope.SheetRowContent(
  @DrawableRes icon: Int?,
  label: String,
  labelColor: Color,
  value: String?,
  valueMaxWidth: Dp,
  valueColor: Color,
  trailing: @Composable RowScope.() -> Unit,
) {
  if (icon != null) {
    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
      LucideIcon(icon = icon, contentDescription = null, tint = labelColor)
    }
  }
  Text(
    text = label,
    modifier = Modifier.weight(1f),
    color = labelColor,
    fontSize = PipkinTokens.FontSize.base,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
  if (value != null) {
    Text(
      text = value,
      modifier = Modifier.widthIn(max = valueMaxWidth),
      color = valueColor,
      fontSize = PipkinTokens.FontSize.base,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  trailing()
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
  Box(modifier = modifier.size(6.dp).clip(CircleShape).background(color))
}

/**
 * Caps the height at half of what the parent offers: the room left after the composer's tray and toolbar for its
 * field, or after the header for a dialog card. Taller content scrolls instead of crowding out the chat stream.
 */
fun Modifier.atMostHalfTall(): Modifier = layout { measurable, constraints ->
  val cap = if (constraints.hasBoundedHeight) constraints.maxHeight / 2 else constraints.maxHeight
  val placeable = measurable.measure(constraints.copy(maxHeight = cap.coerceAtLeast(constraints.minHeight)))
  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
