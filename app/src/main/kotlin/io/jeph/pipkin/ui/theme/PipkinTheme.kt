package io.jeph.pipkin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle

val LocalPipkinColors = staticCompositionLocalOf { DarkPipkinColors }

/** Shorthand for the active palette inside composables. */
val pipkinColors: PipkinColors
  @Composable
  @ReadOnlyComposable
  get() = LocalPipkinColors.current

@Composable
fun PipkinTheme(mode: ThemeMode, content: @Composable () -> Unit) {
  val dark =
    when (mode) {
      ThemeMode.System -> isSystemInDarkTheme()
      ThemeMode.Light -> false
      ThemeMode.Dark -> true
    }
  val colors = if (dark) DarkPipkinColors else LightPipkinColors
  val base = if (dark) darkColorScheme() else lightColorScheme()
  val scheme =
    base.copy(
      primary = colors.accent,
      onPrimary = colors.accentForeground,
      primaryContainer = colors.accentSoft,
      onPrimaryContainer = colors.accent,
      secondary = colors.accent,
      onSecondary = colors.accentForeground,
      secondaryContainer = colors.accentSoft,
      onSecondaryContainer = colors.accent,
      tertiary = colors.accent,
      onTertiary = colors.accentForeground,
      background = colors.surface0,
      onBackground = colors.foreground,
      surface = colors.surface0,
      onSurface = colors.foreground,
      surfaceVariant = colors.surface2,
      onSurfaceVariant = colors.foregroundMuted,
      surfaceTint = colors.accent,
      inverseSurface = colors.foreground,
      inverseOnSurface = colors.surface0,
      inversePrimary = colors.accent,
      surfaceContainerLowest = colors.surface0,
      surfaceContainerLow = colors.surface1,
      surfaceContainer = colors.surface1,
      surfaceContainerHigh = colors.surface2,
      surfaceContainerHighest = colors.surface3,
      surfaceBright = colors.surface2,
      surfaceDim = colors.surface0,
      outline = colors.border,
      outlineVariant = colors.borderAccent,
      error = colors.destructive,
      onError = colors.destructiveForeground,
    )
  CompositionLocalProvider(LocalPipkinColors provides colors) {
    MaterialTheme(colorScheme = scheme, typography = PipkinTypography) {
      CompositionLocalProvider(LocalContentColor provides colors.foreground, content = content)
    }
  }
}

/**
 * Material's type scale in Manrope with ligatures off. The body styles take the base size; there's no colour in
 * them: MaterialTheme's LocalTextStyle is bodyLarge and buttons merge into it, so a colour would override their
 * content colour. Text colour comes from LocalContentColor instead.
 */
private val PipkinTypography = Typography().run {
  val font = TextStyle(fontFamily = PipkinFonts.ui, fontFeatureSettings = PipkinFonts.FEATURES)
  val body = font.merge(TextStyle(fontSize = PipkinTokens.FontSize.base))
  Typography(
    displayLarge = displayLarge.merge(font),
    displayMedium = displayMedium.merge(font),
    displaySmall = displaySmall.merge(font),
    headlineLarge = headlineLarge.merge(font),
    headlineMedium = headlineMedium.merge(font),
    headlineSmall = headlineSmall.merge(font),
    titleLarge = titleLarge.merge(font),
    titleMedium = titleMedium.merge(font),
    titleSmall = titleSmall.merge(font),
    bodyLarge = bodyLarge.merge(body),
    bodyMedium = bodyMedium.merge(body),
    bodySmall = bodySmall.merge(font),
    labelLarge = labelLarge.merge(font),
    labelMedium = labelMedium.merge(font),
    labelSmall = labelSmall.merge(font),
  )
}
