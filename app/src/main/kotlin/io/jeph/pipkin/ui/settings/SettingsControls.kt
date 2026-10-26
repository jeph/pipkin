package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** The label over a settings card or list: small, bold and spaced out, in the muted colour. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier.padding(horizontal = PipkinTokens.Spacing.s12),
    color = pipkinColors.foregroundMuted,
    fontSize = SECTION_LABEL_SIZE,
    fontWeight = FontWeight.Bold,
    letterSpacing = SECTION_LABEL_TRACKING,
  )
}

private val SECTION_LABEL_SIZE = 11.5.sp
private val SECTION_LABEL_TRACKING = 0.06.em

/**
 * A settings switch. Its row is the toggleable target, so the switch only shows the state. The unchecked thumb takes
 * the muted text colour, since Material's outline-coloured one all but vanishes on the surface3 track.
 */
@Composable
internal fun SettingsSwitch(checked: Boolean) {
  val colors = pipkinColors
  Switch(
    checked = checked,
    onCheckedChange = null,
    colors = SwitchDefaults.colors(
      checkedThumbColor = colors.accentForeground,
      checkedTrackColor = colors.accent,
      checkedBorderColor = Color.Transparent,
      uncheckedThumbColor = colors.foregroundMuted,
      uncheckedTrackColor = colors.surface3,
      uncheckedBorderColor = Color.Transparent,
    ),
  )
}

/** A number between minus and plus buttons labelled [fewer] and [more], kept within [range]. */
@Composable
internal fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit, fewer: String, more: String) {
  val colors = pipkinColors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    RoundIconButton(
      icon = R.drawable.ic_lucide_minus,
      contentDescription = fewer,
      onClick = { onChange(value - 1) },
      modifier = Modifier.size(32.dp),
      enabled = value > range.first,
      background = colors.surface2,
      tint = colors.foreground,
    )
    Text(
      text = value.toString(),
      // Announced as it changes, since the buttons on either side only say which way they go.
      modifier = Modifier.widthIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
      fontWeight = FontWeight.Bold,
      style = LocalTextStyle.current.merge(TextStyle(fontFeatureSettings = PipkinFonts.TABULAR)),
      textAlign = TextAlign.Center,
    )
    RoundIconButton(
      icon = R.drawable.ic_lucide_plus,
      contentDescription = more,
      onClick = { onChange(value + 1) },
      modifier = Modifier.size(32.dp),
      enabled = value < range.last,
      background = colors.surface2,
      tint = colors.foreground,
    )
  }
}

/**
 * The latest line of a command's output under the [SettingsRow] running it, kept to one line so the row doesn't jump
 * as the output changes.
 */
@Composable
internal fun SettingsProgressLine(text: String) {
  Text(
    text = text,
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s16, bottom = PipkinTokens.Spacing.s12),
    color = pipkinColors.foregroundMuted,
    fontFamily = PipkinFonts.mono,
    fontSize = PipkinTokens.FontSize.code,
    lineHeight = PipkinTokens.LineHeight.code,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/** The shape of a settings form field: the same rounded well as a dialog's field. */
internal val SettingsFieldShape = RoundedCornerShape(14.dp)

/** A settings form field's colours: a raised well with a hairline that turns cobalt while focused. */
@Composable
internal fun settingsFieldColors(): TextFieldColors {
  val colors = pipkinColors
  return OutlinedTextFieldDefaults.colors(
    focusedContainerColor = colors.surface1,
    unfocusedContainerColor = colors.surface1,
    disabledContainerColor = colors.surface1,
    errorContainerColor = colors.surface1,
    focusedBorderColor = colors.accent,
    unfocusedBorderColor = colors.border,
    cursorColor = colors.accent,
  )
}

/**
 * A page's full-width Save: a filled cobalt pill like [io.jeph.pipkin.ui.components.PillButton], which shows the
 * simmering pot in place of its label while [saving].
 */
@Composable
internal fun SettingsSaveButton(label: String, saving: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val colors = pipkinColors
  Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
    enabled = enabled,
    shape = CircleShape,
    colors = ButtonDefaults.buttonColors(
      containerColor = colors.accent,
      contentColor = colors.accentForeground,
      disabledContainerColor = colors.accent.copy(alpha = DISABLED_ALPHA),
      disabledContentColor = colors.accentForeground.copy(alpha = DISABLED_ALPHA),
    ),
    contentPadding = PaddingValues(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
  ) {
    if (saving) {
      SimmeringPot(tint = colors.accentForeground)
    } else {
      Text(text = label, fontSize = PipkinTokens.FontSize.base, fontWeight = FontWeight.SemiBold)
    }
  }
}

private const val DISABLED_ALPHA = 0.4f
