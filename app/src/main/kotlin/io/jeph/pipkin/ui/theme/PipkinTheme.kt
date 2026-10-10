package io.jeph.pipkin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
      background = colors.surface0,
      onBackground = colors.foreground,
      surface = colors.surface0,
      onSurface = colors.foreground,
      surfaceVariant = colors.surface2,
      onSurfaceVariant = colors.foregroundMuted,
      surfaceContainerLowest = colors.surface0,
      surfaceContainerLow = colors.surface1,
      surfaceContainer = colors.surface1,
      surfaceContainerHigh = colors.surface2,
      surfaceContainerHighest = colors.surface3,
      outline = colors.border,
      outlineVariant = colors.borderAccent,
      error = colors.destructive,
      onError = colors.accentForeground,
    )
  val typography = MaterialTheme.typography
  // No colour here: MaterialTheme's LocalTextStyle is bodyLarge and buttons merge into it, so a colour would
  // override their content colour. Text colour comes from LocalContentColor instead.
  val bodyStyle = TextStyle(fontSize = PipkinTokens.FontSize.base)
  CompositionLocalProvider(LocalPipkinColors provides colors) {
    MaterialTheme(
      colorScheme = scheme,
      typography =
        typography.copy(
          bodyLarge = typography.bodyLarge.merge(bodyStyle),
          bodyMedium = typography.bodyMedium.merge(bodyStyle),
        ),
    ) {
      CompositionLocalProvider(LocalContentColor provides colors.foreground, content = content)
    }
  }
}
