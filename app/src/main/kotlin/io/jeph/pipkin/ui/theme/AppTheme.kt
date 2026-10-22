package io.jeph.pipkin.ui.theme

/** The theme picked in Settings: System follows the phone's dark mode with Porcelain or Stone. */
enum class AppTheme {
  System,
  Porcelain,
  Clay,
  Cobalt,
  Stone,
  ;

  /** This theme's palette, with [systemDark] saying whether the phone is in dark mode. */
  fun colors(systemDark: Boolean): PipkinColors = when (this) {
    System -> if (systemDark) StoneColors else PorcelainColors
    Porcelain -> PorcelainColors
    Clay -> ClayColors
    Cobalt -> CobaltColors
    Stone -> StoneColors
  }
}
