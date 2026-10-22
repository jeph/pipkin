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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

/** A compact two-row layout, so the terminal keeps most of the room above the keyboard. */
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
    modifier = modifier
      .fillMaxWidth()
      .background(pipkinColors.surface1)
      .topHairline(pipkinColors.border)
      .padding(horizontal = PipkinTokens.Spacing.s6, vertical = PipkinTokens.Spacing.s6),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    for (row in ROWS) {
      Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
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

/** A 1dp line along the top edge, setting a bar off from the terminal above it. */
internal fun Modifier.topHairline(color: Color): Modifier = drawBehind {
  drawRect(color = color, size = Size(size.width, 1.dp.toPx()))
}

/**
 * A key on the bar. A modifier's three states read apart at a glance: off is a plain key, armed (for the next key
 * only) is washed in the accent, and locked is filled with it.
 */
@Composable
private fun RowScope.BarButton(
  label: String? = null,
  description: String? = label,
  @DrawableRes icon: Int? = null,
  sticky: StickyState = StickyState.INACTIVE,
  onClick: () -> Unit,
) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(KEY_RADIUS)
  val (background, content) = when (sticky) {
    StickyState.INACTIVE -> colors.surface2 to colors.foreground
    StickyState.ARMED -> colors.accentSoft to colors.accent
    StickyState.LOCKED -> colors.accent to colors.accentForeground
  }
  val ring = if (sticky == StickyState.ARMED) {
    Modifier.border(1.dp, colors.accent.copy(alpha = ARMED_RING_ALPHA), shape)
  } else {
    Modifier
  }
  Box(
    modifier = Modifier
      .weight(1f)
      .height(36.dp)
      .clip(shape)
      .background(background)
      .then(ring)
      .clickable(onClick = onClick)
      .semantics { description?.let { contentDescription = it } },
    contentAlignment = Alignment.Center,
  ) {
    if (icon != null) {
      LucideIcon(icon = icon, contentDescription = null, tint = content)
    } else if (label != null) {
      Text(
        text = label,
        color = content,
        fontSize = PipkinTokens.FontSize.meta,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

private val KEY_RADIUS = 10.dp
private const val ARMED_RING_ALPHA = 0.5f
