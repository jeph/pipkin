package io.jeph.pipkin.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Paseo's palette with the accent swapped for the launcher icon's terracotta (https://github.com/getpaseo/paseo,
 * commit b26b923; Copyright (c) 2025-present Mohamed Boudra, Apache License 2.0, see `assets/licenses/paseo.txt`).
 */
@Immutable
data class PipkinColors(
  val isDark: Boolean,
  val surface0: Color,
  val surface1: Color,
  val surface2: Color,
  val surface3: Color,
  val surface4: Color,
  val sidebar: Color,
  val foreground: Color,
  val foregroundMuted: Color,
  val foregroundExtraMuted: Color,
  val border: Color,
  val borderAccent: Color,
  val accent: Color,
  val accentForeground: Color,
  val destructive: Color,
  val dotSuccess: Color,
  val dotDanger: Color,
  val dotWarning: Color,
)

val DarkPipkinColors =
  PipkinColors(
    isDark = true,
    surface0 = Color(0xFF181B1A),
    surface1 = Color(0xFF1E2120),
    surface2 = Color(0xFF272A29),
    surface3 = Color(0xFF434645),
    surface4 = Color(0xFF595B5B),
    sidebar = Color(0xFF141716),
    foreground = Color(0xFFFAFAFA),
    foregroundMuted = Color(0xFFA1A5A4),
    foregroundExtraMuted = Color(0xFF717574),
    border = Color(0xFF252B2A),
    borderAccent = Color(0xFF2F3534),
    accent = Color(0xFFDD774E),
    accentForeground = Color(0xFFFFFFFF),
    destructive = Color(0xFFC64F43),
    dotSuccess = Color(0xFF35C264),
    dotDanger = Color(0xFFF7796D),
    dotWarning = Color(0xFFDB932E),
  )

val LightPipkinColors =
  PipkinColors(
    isDark = false,
    surface0 = Color(0xFFFFFFFF),
    surface1 = Color(0xFFFAFAFA),
    surface2 = Color(0xFFF4F4F5),
    surface3 = Color(0xFFE4E4E7),
    surface4 = Color(0xFFD4D4D8),
    sidebar = Color(0xFFF4F4F5),
    foreground = Color(0xFF1A1A1E),
    foregroundMuted = Color(0xFF71717A),
    foregroundExtraMuted = Color(0xFFA1A1AA),
    border = Color(0xFFE4E4E7),
    borderAccent = Color(0xFFECECF1),
    accent = Color(0xFFBD5132),
    accentForeground = Color(0xFFFFFFFF),
    destructive = Color(0xFFB04138),
    dotSuccess = Color(0xFF299F51),
    dotDanger = Color(0xFFF12E2F),
    dotWarning = Color(0xFFB37824),
  )
