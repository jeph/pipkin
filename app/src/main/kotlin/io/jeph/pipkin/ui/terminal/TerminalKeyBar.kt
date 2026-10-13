package io.jeph.pipkin.ui.terminal

import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sagernet.libghostty.GhosttyExtraKeysState
import io.github.sagernet.libghostty.GhosttyExtraKeysState.StickyState
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

private sealed interface BarKey {
  data class Key(@StringRes val label: Int, val keyCode: Int, val symbol: String? = null) : BarKey

  data object Ctrl : BarKey

  data object Alt : BarKey

  data object Shift : BarKey

  data object Paste : BarKey

  data object KeyboardToggle : BarKey
}

/** Paseo's compact two-row layout. */
private val ROWS = listOf(
  listOf(
    BarKey.Key(R.string.terminal_key_esc, KeyEvent.KEYCODE_ESCAPE),
    BarKey.Key(R.string.terminal_key_tab, KeyEvent.KEYCODE_TAB),
    BarKey.Ctrl,
    BarKey.Key(R.string.terminal_key_up, KeyEvent.KEYCODE_DPAD_UP, "↑"),
    BarKey.Shift,
    BarKey.KeyboardToggle,
  ),
  listOf(
    BarKey.Alt,
    BarKey.Paste,
    BarKey.Key(R.string.terminal_key_left, KeyEvent.KEYCODE_DPAD_LEFT, "←"),
    BarKey.Key(R.string.terminal_key_down, KeyEvent.KEYCODE_DPAD_DOWN, "↓"),
    BarKey.Key(R.string.terminal_key_right, KeyEvent.KEYCODE_DPAD_RIGHT, "→"),
    BarKey.Key(R.string.terminal_key_enter, KeyEvent.KEYCODE_ENTER),
  ),
)

/**
 * Esc, Tab, arrows, Enter, modifiers, Paste and a keyboard toggle above the soft keyboard. Ctrl and Alt are
 * libghostty's: they apply to the next key from the bar or the keyboard, and a double tap locks them. Shift only
 * applies to the bar's own keys, since the keyboard has its own. The buttons never take focus, so the keyboard stays
 * up while they are pressed.
 */
@Composable
internal fun TerminalKeyBar(
  extraKeys: GhosttyExtraKeysState,
  shift: Boolean,
  keyboardShown: Boolean,
  onToggleShift: () -> Unit,
  onKey: (keyCode: Int) -> Unit,
  onPaste: () -> Unit,
  onToggleKeyboard: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val ctrl by extraKeys.ctrl.collectAsStateWithLifecycle()
  val alt by extraKeys.alt.collectAsStateWithLifecycle()
  Column(
    modifier = modifier.fillMaxWidth().background(pipkinColors.surface1).padding(PipkinTokens.Spacing.s4),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    for (row in ROWS) {
      Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4)) {
        for (key in row) {
          when (key) {
            is BarKey.Key -> BarButton(
              label = key.symbol ?: stringResource(key.label),
              description = stringResource(key.label),
              onClick = { onKey(key.keyCode) },
            )

            BarKey.Ctrl -> BarButton(
              label = stringResource(R.string.terminal_key_ctrl),
              sticky = ctrl,
              onClick = extraKeys::toggleCtrl,
            )

            BarKey.Alt -> BarButton(
              label = stringResource(R.string.terminal_key_alt),
              sticky = alt,
              onClick = extraKeys::toggleAlt,
            )

            BarKey.Shift -> BarButton(
              label = stringResource(R.string.terminal_key_shift),
              sticky = if (shift) StickyState.ARMED else StickyState.INACTIVE,
              onClick = onToggleShift,
            )

            BarKey.Paste -> BarButton(label = stringResource(R.string.terminal_key_paste), onClick = onPaste)

            BarKey.KeyboardToggle -> BarButton(
              icon = if (keyboardShown) R.drawable.ic_lucide_keyboard_off else R.drawable.ic_lucide_keyboard,
              description = stringResource(
                if (keyboardShown) R.string.terminal_hide_keyboard else R.string.terminal_show_keyboard,
              ),
              onClick = onToggleKeyboard,
            )
          }
        }
      }
    }
  }
}

/** An armed modifier fills with the accent; a locked one also gets an outline. */
@Composable
private fun RowScope.BarButton(
  label: String? = null,
  description: String? = label,
  @DrawableRes icon: Int? = null,
  sticky: StickyState = StickyState.INACTIVE,
  onClick: () -> Unit,
) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.md)
  val active = sticky != StickyState.INACTIVE
  val outline = if (sticky == StickyState.LOCKED) Modifier.border(2.dp, colors.foreground, shape) else Modifier
  Box(
    modifier = Modifier
      .weight(1f)
      .height(36.dp)
      .clip(shape)
      .background(if (active) colors.accent else colors.surface2)
      .then(outline)
      .clickable(onClick = onClick)
      .semantics { description?.let { contentDescription = it } },
    contentAlignment = Alignment.Center,
  ) {
    if (icon != null) {
      LucideIcon(icon = icon, contentDescription = null, tint = colors.foreground)
    } else if (label != null) {
      Text(
        text = label,
        color = if (active) colors.accentForeground else colors.foreground,
        fontSize = PipkinTokens.FontSize.sm,
      )
    }
  }
}
