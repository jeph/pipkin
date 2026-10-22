package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.ui.components.SimmeringPot
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
