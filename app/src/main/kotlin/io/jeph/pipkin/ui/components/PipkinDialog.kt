package io.jeph.pipkin.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** A dialog's confirm button: its [label], enabled while [enabled], and red when [destructive], cobalt otherwise. */
class DialogConfirm(
  val label: String,
  val onClick: () -> Unit,
  val enabled: Boolean = true,
  val destructive: Boolean = false,
)

/**
 * Pipkin's dialog: an optional [icon] tile naming the kind of action, a bold [title] that wraps, the [text], any
 * [content] such as a field, and a row of two pill buttons, the tonal [dismissLabel] first, then [confirm]. The tile
 * takes the confirm button's colour. Back and a tap outside call [onDismiss] too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipkinDialog(
  title: String,
  confirm: DialogConfirm,
  dismissLabel: String,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  text: String? = null,
  @DrawableRes icon: Int? = null,
  content: @Composable ColumnScope.() -> Unit = {},
) {
  val colors = pipkinColors
  // Wider than Material's default, 24dp in from the screen's edges, so two-word buttons fit on one line.
  BasicAlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier.padding(horizontal = PipkinTokens.Spacing.s24),
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(DIALOG_SHAPE)
        .background(colors.surface1)
        .padding(start = 18.dp, top = 20.dp, end = 18.dp, bottom = 14.dp),
    ) {
      if (icon != null) DialogIconTile(icon, confirm.destructive)
      Text(
        text = title,
        color = colors.foreground,
        fontSize = 19.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.02).em,
        lineHeight = 24.sp,
      )
      if (text != null) {
        Text(
          text = text,
          modifier = Modifier.padding(top = PipkinTokens.Spacing.s8),
          color = colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.base,
          lineHeight = 21.sp,
        )
      }
      content()
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
      ) {
        PillButton(label = dismissLabel, onClick = onDismiss, modifier = Modifier.weight(1f), tone = PillTone.Tonal)
        PillButton(
          label = confirm.label,
          onClick = confirm.onClick,
          modifier = Modifier.weight(1f),
          tone = if (confirm.destructive) PillTone.Destructive else PillTone.Primary,
          enabled = confirm.enabled,
        )
      }
    }
  }
}

/** The tile naming a dialog's kind of action, red for a [destructive] one and cobalt otherwise. */
@Composable
private fun DialogIconTile(@DrawableRes icon: Int, destructive: Boolean) {
  val colors = pipkinColors
  Box(
    modifier = Modifier
      .padding(bottom = PipkinTokens.Spacing.s12)
      .size(40.dp)
      .clip(RoundedCornerShape(14.dp))
      .background(if (destructive) colors.dangerSoft else colors.accentSoft),
    contentAlignment = Alignment.Center,
  ) {
    LucideIcon(
      icon = icon,
      contentDescription = null,
      size = PipkinTokens.IconSize.lg,
      tint = if (destructive) colors.destructive else colors.accent,
    )
  }
}

private val DIALOG_SHAPE = RoundedCornerShape(28.dp)

/** The colours of a [PillButton]. */
enum class PillTone { Primary, Destructive, Tonal }

/** A fully rounded button, 40dp tall, whose [label] wraps onto a second line rather than cutting off. */
@Composable
fun PillButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  tone: PillTone = PillTone.Primary,
  enabled: Boolean = true,
  @DrawableRes icon: Int? = null,
) {
  val colors = pipkinColors
  val (container, content) = when (tone) {
    PillTone.Primary -> colors.accent to colors.accentForeground
    PillTone.Destructive -> colors.destructive to colors.destructiveForeground
    PillTone.Tonal -> colors.surface2 to colors.foregroundMuted
  }
  Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = 40.dp),
    enabled = enabled,
    shape = RoundedCornerShape(20.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = container,
      contentColor = content,
      disabledContainerColor = container.copy(alpha = DISABLED_ALPHA * container.alpha),
      disabledContentColor = content.copy(alpha = DISABLED_ALPHA),
    ),
    contentPadding = PaddingValues(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
  ) {
    if (icon != null) {
      LucideIcon(icon = icon, contentDescription = null, size = 15.dp, tint = LocalContentColor.current)
      Box(Modifier.size(PipkinTokens.Spacing.s8))
    }
    Text(
      text = label,
      fontSize = PipkinTokens.FontSize.base,
      fontWeight = FontWeight.SemiBold,
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private const val DISABLED_ALPHA = 0.4f

/** A dialog's one-line field: an inset well under a cobalt ring while focused, with the [placeholder] while it's empty. */
@Composable
fun DialogTextField(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String? = null,
) {
  val colors = pipkinColors
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val shape = RoundedCornerShape(14.dp)
  val style = LocalTextStyle.current.merge(
    TextStyle(color = colors.foreground, fontSize = PipkinTokens.FontSize.content),
  )
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.fillMaxWidth().padding(top = 14.dp),
    textStyle = style,
    singleLine = true,
    cursorBrush = SolidColor(colors.accent),
    interactionSource = interaction,
    decorationBox = { field ->
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(shape)
          .background(colors.surface0)
          .border(if (focused) 1.5.dp else 1.dp, if (focused) colors.accent else colors.border, shape)
          .heightIn(min = 44.dp)
          .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        if (value.text.isEmpty() && placeholder != null) {
          Text(text = placeholder, style = style, color = colors.foregroundMuted, maxLines = 1)
        }
        field()
      }
    },
  )
}
