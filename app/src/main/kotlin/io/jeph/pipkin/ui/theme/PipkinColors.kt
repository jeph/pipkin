package io.jeph.pipkin.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Pipkin's Cobalt palette: navy (dark) or porcelain (light) surfaces with a cobalt accent. */
@Immutable
data class PipkinColors(
  val isDark: Boolean,
  val surface0: Color,
  val surface1: Color,
  val surface2: Color,
  val surface3: Color,
  val sidebar: Color,
  val foreground: Color,
  val foregroundMuted: Color,
  val foregroundExtraMuted: Color,
  val border: Color,
  val borderAccent: Color,
  val accent: Color,
  val accentForeground: Color,
  /** A wash of [accent] behind accent-coloured text, such as a selected row or the tab count. */
  val accentSoft: Color,
  /** The accent on an inverse surface, such as a snackbar's action, light or dark enough to read there. */
  val accentInverse: Color,
  val destructive: Color,
  val destructiveForeground: Color,
  val dotSuccess: Color,
  val dotDanger: Color,
  val dotWarning: Color,
  /** Informational notices and the cyan ANSI colour. */
  val info: Color,
  /** Extension messages and the magenta ANSI colour. */
  val extension: Color,
) {
  val successSoft: Color get() = dotSuccess.copy(alpha = if (isDark) 0.13f else 0.10f)
  val warningSoft: Color get() = dotWarning.copy(alpha = if (isDark) 0.14f else 0.12f)
  val dangerSoft: Color get() = dotDanger.copy(alpha = if (isDark) 0.14f else 0.10f)
  val infoSoft: Color get() = info.copy(alpha = 0.12f)
}

val DarkPipkinColors =
  PipkinColors(
    isDark = true,
    surface0 = Color(0xFF0C0F1A),
    surface1 = Color(0xFF141828),
    surface2 = Color(0xFF1C2236),
    surface3 = Color(0xFF2A3150),
    sidebar = Color(0xFF0C0F1A),
    foreground = Color(0xFFE8EBF7),
    foregroundMuted = Color(0xFF9CA3BF),
    foregroundExtraMuted = Color(0xFF636A88),
    border = Color(0xFF222944),
    borderAccent = Color(0xFF2A3150),
    accent = Color(0xFF7C9CFF),
    accentForeground = Color(0xFF0A1240),
    accentSoft = Color(0x247C9CFF),
    accentInverse = Color(0xFF2F4BD8),
    destructive = Color(0xFFFF6B7A),
    destructiveForeground = Color(0xFF2A0710),
    dotSuccess = Color(0xFF5FD49A),
    dotDanger = Color(0xFFFF6B7A),
    dotWarning = Color(0xFFFFC94D),
    info = Color(0xFF5ED3E6),
    extension = Color(0xFFD49CFF),
  )

// Warning and info are darker than the mockup's #A36A00 and #0E7C8C so they keep 4.5:1 on raised surfaces.
val LightPipkinColors =
  PipkinColors(
    isDark = false,
    surface0 = Color(0xFFF6F7FB),
    surface1 = Color(0xFFEBEEF7),
    surface2 = Color(0xFFDFE3F0),
    surface3 = Color(0xFFCCD2E6),
    sidebar = Color(0xFFF6F7FB),
    foreground = Color(0xFF111633),
    foregroundMuted = Color(0xFF565D7A),
    foregroundExtraMuted = Color(0xFF959BB5),
    border = Color(0xFFD9DEED),
    borderAccent = Color(0xFFCCD2E6),
    accent = Color(0xFF2F4BD8),
    accentForeground = Color(0xFFFFFFFF),
    accentSoft = Color(0x172F4BD8),
    accentInverse = Color(0xFFA9BBFF),
    destructive = Color(0xFFC42B3C),
    destructiveForeground = Color(0xFFFFFFFF),
    dotSuccess = Color(0xFF157A4A),
    dotDanger = Color(0xFFC42B3C),
    dotWarning = Color(0xFF875700),
    info = Color(0xFF0B6B78),
    extension = Color(0xFF8A3FC4),
  )
