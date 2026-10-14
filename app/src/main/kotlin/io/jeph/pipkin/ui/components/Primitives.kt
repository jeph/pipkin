package io.jeph.pipkin.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.math.roundToInt

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

/** Paseo's 28dp circular icon button. */
@Composable
fun IconButton28(
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
    modifier =
      modifier
        .alpha(if (enabled) 1f else 0.5f)
        .size(28.dp)
        .clip(CircleShape)
        .background(background)
        .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    LucideIcon(icon = icon, contentDescription = contentDescription, size = iconSize, tint = tint)
  }
}

/** 56dp header with an 8dp top pad below the status bar and a 1dp bottom border. */
@Composable
fun HeaderBar(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  leading: @Composable RowScope.() -> Unit = {},
  trailing: @Composable RowScope.() -> Unit = {},
) {
  val colors = pipkinColors
  Column(modifier = modifier.fillMaxWidth().background(colors.surface0)) {
    Row(
      modifier =
        Modifier
          .windowInsetsPadding(WindowInsets.statusBars)
          .padding(top = PipkinTokens.Spacing.s8)
          .fillMaxWidth()
          .heightIn(min = 56.dp)
          .padding(horizontal = PipkinTokens.Spacing.s12),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      leading()
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          color = colors.foreground,
          fontSize = PipkinTokens.FontSize.base,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
          Text(
            text = subtitle,
            color = colors.foregroundMuted,
            fontSize = PipkinTokens.FontSize.sm,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      trailing()
    }
    HorizontalDivider(thickness = 1.dp, color = colors.border)
  }
}

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
 * A modal bottom sheet. With [heightFraction] the sheet is that share of the window tall whatever its
 * content, and [content] can fill the space with weights. With [maxHeightFraction] the sheet wraps its
 * content up to that share; give the scrolling part `weight(1f, fill = false)` so it scrolls there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipkinSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  heightFraction: Float? = null,
  maxHeightFraction: Float? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val windowHeight = LocalWindowInfo.current.containerSize.height
  val sizing = when {
    heightFraction != null -> Modifier.sheetHeight(heightFraction, windowHeight, fill = true)
    maxHeightFraction != null -> Modifier.sheetHeight(maxHeightFraction, windowHeight, fill = false)
    else -> Modifier
  }
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    sheetState = sheetState,
    containerColor = pipkinColors.surface1,
    contentColor = pipkinColors.foreground,
  ) {
    Column(
      modifier = Modifier
        .then(sizing)
        .navigationBarsPadding()
        .padding(bottom = PipkinTokens.Spacing.s16),
      content = content,
    )
  }
}

/**
 * Sizes the sheet's content so the whole sheet is [fraction] of the window, or at most that when not
 * [fill]. Sizing the sheet itself would also shrink the space Material positions it in, pinning it to
 * the top of the screen. The sheet spans the window, so the drag handle and insets above the content
 * take `windowHeight - maxHeight`.
 */
private fun Modifier.sheetHeight(fraction: Float, windowHeight: Int, fill: Boolean): Modifier =
  layout { measurable, constraints ->
    val height = (constraints.maxHeight - windowHeight * (1 - fraction)).roundToInt()
      .coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(
      constraints.copy(minHeight = if (fill) height else constraints.minHeight, maxHeight = height),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
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
