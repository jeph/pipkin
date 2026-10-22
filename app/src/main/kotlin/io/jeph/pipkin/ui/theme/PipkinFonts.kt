package io.jeph.pipkin.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import io.jeph.pipkin.R

/** Manrope for the interface and JetBrains Mono for code, both from the APK's fonts. */
object PipkinFonts {
  /** Ligatures and contextual alternates off, so `->`, `!=` and `...` show as typed. */
  const val FEATURES = "liga 0, calt 0"

  /** [FEATURES] with tabular figures, for counters and timers that tick, so their digits don't jitter. */
  const val TABULAR = "liga 0, calt 0, tnum"

  private val weights =
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)

  val ui = FontFamily(
    weights.map {
      Font(R.font.manrope, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
    },
  )

  val mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
  )

  /**
   * Loads both families into Compose's shared typeface cache. Compose loads a resource font on the main thread the
   * first time text uses it; called off the main thread as the process starts, the first frame usually finds them
   * loaded already.
   */
  suspend fun preload(context: Context) {
    val resolver = createFontFamilyResolver(context)
    resolver.preload(ui)
    resolver.preload(mono)
  }
}
