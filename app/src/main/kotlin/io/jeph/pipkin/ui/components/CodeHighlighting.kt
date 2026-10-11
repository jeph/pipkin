package io.jeph.pipkin.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * [code] coloured for the fence's [language], or plain when Highlights doesn't know it. Highlighting runs
 * off the main thread, one pass at a time with the latest text, and while a streamed block grows the
 * colours already computed stay on its start instead of flashing back to plain.
 */
@Composable
internal fun rememberHighlightedCode(code: String, language: String?): AnnotatedString {
  val syntax = language?.let(::syntaxLanguage) ?: return AnnotatedString(code)
  val dark = pipkinColors.isDark
  val latestCode by rememberUpdatedState(code)
  var highlighted by remember(syntax, dark) { mutableStateOf<AnnotatedString?>(null) }
  LaunchedEffect(syntax, dark) {
    snapshotFlow { latestCode }.conflate().collect { text ->
      highlighted = withContext(Dispatchers.Default) { highlightCode(text, syntax, dark) }
    }
  }
  return withPlainTail(highlighted, code)
}

/** [highlighted] extended with the rest of [code] when [code] continues it; otherwise [code] unstyled. */
internal fun withPlainTail(highlighted: AnnotatedString?, code: String): AnnotatedString = when {
  highlighted == null || !code.startsWith(highlighted.text) -> AnnotatedString(code)
  highlighted.length == code.length -> highlighted
  else -> highlighted + AnnotatedString(code.substring(highlighted.length))
}

/**
 * The Highlights language for a fence's info string, by name or common alias. JSON gets the generic
 * analyzer, which colours its strings, numbers and literals well; other unknown languages stay plain,
 * since the generic analyzer marks every language's keywords and would colour prose and logs.
 */
internal fun syntaxLanguage(fence: String): SyntaxLanguage? {
  val name = fence.trim().substringBefore(' ').lowercase()
  return ALIASES[name] ?: SyntaxLanguage.getByName(name)?.takeUnless { it == SyntaxLanguage.DEFAULT }
}

/**
 * [code] with Highlights' colours for [syntax]. Each call analyzes the whole text: Highlights' incremental
 * mode (setCode on a reused instance) reports ranges shifted past the text as it grows.
 */
internal fun highlightCode(code: String, syntax: SyntaxLanguage, dark: Boolean): AnnotatedString =
  buildAnnotatedString {
    append(code)
    val highlights = Highlights.Builder(code = code, language = syntax, theme = if (dark) DarkTheme else LightTheme)
    for (highlight in highlights.build().getHighlights()) {
      val style = when (highlight) {
        is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
        is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
      }
      // Kept inside the text: a range past the end would throw and take the app down with it.
      val start = highlight.location.start.coerceIn(0, code.length)
      val end = highlight.location.end.coerceIn(start, code.length)
      if (end > start) addStyle(style, start, end)
    }
  }

private val ALIASES = mapOf(
  "json" to SyntaxLanguage.DEFAULT,
  "jsonc" to SyntaxLanguage.DEFAULT,
  "kt" to SyntaxLanguage.KOTLIN,
  "kts" to SyntaxLanguage.KOTLIN,
  "js" to SyntaxLanguage.JAVASCRIPT,
  "jsx" to SyntaxLanguage.JAVASCRIPT,
  "mjs" to SyntaxLanguage.JAVASCRIPT,
  "cjs" to SyntaxLanguage.JAVASCRIPT,
  "ts" to SyntaxLanguage.TYPESCRIPT,
  "tsx" to SyntaxLanguage.TYPESCRIPT,
  "mts" to SyntaxLanguage.TYPESCRIPT,
  "py" to SyntaxLanguage.PYTHON,
  "python3" to SyntaxLanguage.PYTHON,
  "sh" to SyntaxLanguage.SHELL,
  "bash" to SyntaxLanguage.SHELL,
  "zsh" to SyntaxLanguage.SHELL,
  "console" to SyntaxLanguage.SHELL,
  "h" to SyntaxLanguage.C,
  "c++" to SyntaxLanguage.CPP,
  "cc" to SyntaxLanguage.CPP,
  "cxx" to SyntaxLanguage.CPP,
  "hpp" to SyntaxLanguage.CPP,
  "cs" to SyntaxLanguage.CSHARP,
  "c#" to SyntaxLanguage.CSHARP,
  "rs" to SyntaxLanguage.RUST,
  "rb" to SyntaxLanguage.RUBY,
  "golang" to SyntaxLanguage.GO,
  "pl" to SyntaxLanguage.PERL,
  "coffee" to SyntaxLanguage.COFFEESCRIPT,
)

/** Paseo's syntax palette (GitHub's), mapped onto Highlights' token kinds. */
private val DarkTheme = SyntaxTheme(
  key = "paseo-dark",
  code = 0xC9D1D9,
  keyword = 0xFF7B72,
  string = 0xA5D6FF,
  literal = 0x79C0FF,
  comment = 0x8B949E,
  metadata = 0xD2A8FF,
  multilineComment = 0x8B949E,
  punctuation = 0xC9D1D9,
  mark = 0xC9D1D9,
)

private val LightTheme = SyntaxTheme(
  key = "paseo-light",
  code = 0x24292F,
  keyword = 0xCF222E,
  string = 0x0A3069,
  literal = 0x0550AE,
  comment = 0x6E7781,
  metadata = 0x8250DF,
  multilineComment = 0x6E7781,
  punctuation = 0x24292F,
  mark = 0x24292F,
)
