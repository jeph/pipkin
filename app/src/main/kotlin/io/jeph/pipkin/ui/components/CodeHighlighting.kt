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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.jeph.pipkin.syntax.CaptureSpan
import io.jeph.pipkin.syntax.Grammar
import io.jeph.pipkin.syntax.LoadedGrammar
import io.jeph.pipkin.syntax.SyntaxSession
import io.jeph.pipkin.syntax.loadGrammar
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * [code] coloured for [language] (a fence name or file extension), or plain when no grammar knows it.
 * Highlighting runs off the main thread, one pass at a time with the latest text, reparsing only what
 * changed; while a streamed block grows, the colours already computed stay on its start instead of
 * flashing back to plain.
 */
@Composable
internal fun rememberHighlightedCode(code: String, language: String?): AnnotatedString {
  val grammar = language?.let(Grammar::forName) ?: return AnnotatedString(code)
  val palette = if (pipkinColors.isDark) DarkPalette else LightPalette
  val assets = LocalContext.current.assets
  val latestCode by rememberUpdatedState(code)
  var highlighted by remember(grammar, palette) { mutableStateOf<AnnotatedString?>(null) }
  LaunchedEffect(grammar, palette) {
    val loaded = withContext(Dispatchers.Default) { loadGrammar(grammar, assets) }
    val colors = captureColors(loaded, palette)
    // Cancelling waits for a running parse to return, so the session is never closed under one.
    SyntaxSession(loaded).use { session ->
      snapshotFlow { latestCode }.conflate().collect { text ->
        highlighted = withContext(Dispatchers.Default) {
          paint(text, session.captures(text) { colors[it] != null }, colors::get)
        }
      }
    }
  }
  return withPlainTail(highlighted, code)
}

/**
 * [code] coloured by [grammar] for the [dark] palette or the light one, in one parse on the calling thread, for callers
 * that colour many texts off the main thread themselves.
 */
internal fun highlightCode(code: String, grammar: LoadedGrammar, dark: Boolean): AnnotatedString {
  val colors = captureColors(grammar, if (dark) DarkPalette else LightPalette)
  return SyntaxSession(grammar).use { session ->
    paint(code, session.captures(code) { colors[it] != null }, colors::get)
  }
}

/** Each of [grammar]'s captures' colour in [palette], by capture index; null for one that colours nothing. */
private fun captureColors(grammar: LoadedGrammar, palette: Map<Token, Color>): List<Color?> =
  grammar.captureNames.map { name -> tokenFor(name)?.let(palette::getValue) }

/** [highlighted] extended with the rest of [code] when [code] continues it; otherwise [code] unstyled. */
internal fun withPlainTail(highlighted: AnnotatedString?, code: String): AnnotatedString = when {
  highlighted == null || !code.startsWith(highlighted.text) -> AnnotatedString(code)
  highlighted.length == code.length -> highlighted
  else -> highlighted + AnnotatedString(code.substring(highlighted.length))
}

/** The extension of [path]'s file name, which [Grammar.forName] reads like a fence name; null without one. */
internal fun fileLanguage(path: String): String? =
  path.substringAfterLast('/').substringAfterLast('.', "").ifEmpty { null }

/**
 * Each line of this text with its styles, split at `\n`, `\r\n` or `\r` like [String.lines]. One pass over the
 * styles, so a whole file's worth of lines costs about its length rather than lines times styles.
 */
internal fun AnnotatedString.splitLines(): List<AnnotatedString> {
  val styles = spanStyles.sortedBy { it.start }
  val lines = mutableListOf<AnnotatedString>()
  var next = 0
  val open = mutableListOf<AnnotatedString.Range<SpanStyle>>()
  fun addLine(start: Int, end: Int) {
    while (next < styles.size && styles[next].start < end) open += styles[next++]
    val lineStyles = open.mapNotNull { style ->
      if (style.end <= start || style.start >= end) return@mapNotNull null
      AnnotatedString.Range(style.item, maxOf(style.start, start) - start, minOf(style.end, end) - start)
    }
    open.removeAll { it.end <= end }
    lines += AnnotatedString(text.substring(start, end), lineStyles)
  }
  // A scan rather than Regex.findAll: on Android each match makes a Matcher that copies the whole text, so a file of
  // many lines would take its length times its line count.
  var start = 0
  var index = 0
  while (index < length) {
    val char = text[index]
    if (char != '\n' && char != '\r') {
      index++
      continue
    }
    addLine(start, index)
    index += if (char == '\r' && text.getOrNull(index + 1) == '\n') 2 else 1
    start = index
  }
  addLine(start, length)
  return lines
}

/** How Pipkin colours a highlight capture. [Plain] takes the text's own colour, even inside a coloured capture. */
internal enum class Token { Plain, Keyword, String, Constant, Comment, Function, Type, Tag }

/**
 * The token a capture name colours as: its own entry, else its parent's (`function.method.builtin`, then
 * `function.method`, then `function`). Null for captures that colour nothing, such as `@_name` helpers.
 */
internal fun tokenFor(capture: String): Token? {
  var name = capture
  while (true) {
    TOKENS[name]?.let { return it }
    if ('.' !in name) return null
    name = name.substringBeforeLast('.')
  }
}

/**
 * [text] coloured by [spans], outermost first as [io.jeph.pipkin.syntax.captureSpans] gives them, so an
 * inner capture repaints its part of an outer one. [colorOf] gives a capture's colour: null leaves what is
 * under it, [Color.Unspecified] makes it plain.
 */
internal fun paint(text: String, spans: List<CaptureSpan>, colorOf: (capture: Int) -> Color?): AnnotatedString {
  val colors = arrayOfNulls<Color>(text.length)
  for (span in spans) {
    val color = colorOf(span.capture) ?: continue
    // Kept inside the text: a range past the end would throw and take the app down with it.
    colors.fill(color, span.start.coerceIn(0, text.length), span.end.coerceIn(0, text.length))
  }
  return buildAnnotatedString {
    append(text)
    var start = 0
    while (start < text.length) {
      val color = colors[start]
      var end = start + 1
      while (end < text.length && colors[end] == color) end++
      if (color != null && color != Color.Unspecified) addStyle(SpanStyle(color = color), start, end)
      start = end
    }
  }
}

private val TOKENS = mapOf(
  "comment" to Token.Comment,
  "string" to Token.String,
  "character" to Token.String,
  "string.escape" to Token.Constant,
  "string.special" to Token.Constant,
  "character.special" to Token.Constant,
  "escape" to Token.Constant,
  "keyword" to Token.Keyword,
  "conditional" to Token.Keyword,
  "repeat" to Token.Keyword,
  "include" to Token.Keyword,
  "exception" to Token.Keyword,
  "operator" to Token.Keyword,
  "preproc" to Token.Keyword,
  "punctuation.special" to Token.Keyword,
  "number" to Token.Constant,
  "identifier.constant" to Token.Constant,
  "float" to Token.Constant,
  "boolean" to Token.Constant,
  "constant" to Token.Constant,
  "attribute" to Token.Constant,
  "tag.attribute" to Token.Constant,
  "variable.builtin" to Token.Constant,
  "function" to Token.Function,
  "method" to Token.Function,
  "constructor" to Token.Function,
  "type" to Token.Type,
  "module" to Token.Type,
  "namespace" to Token.Type,
  "tag" to Token.Tag,
  "tag.delimiter" to Token.Plain,
  "variable" to Token.Plain,
  "property" to Token.Plain,
  "field" to Token.Plain,
  "label" to Token.Plain,
  "punctuation" to Token.Plain,
  "delimiter" to Token.Plain,
  "embedded" to Token.Plain,
  "none" to Token.Plain,
)

/** Paseo's syntax palette (GitHub's Primer), by token. */
private val DarkPalette = mapOf(
  Token.Plain to Color.Unspecified,
  Token.Keyword to Color(0xFFFF7B72),
  Token.String to Color(0xFFA5D6FF),
  Token.Constant to Color(0xFF79C0FF),
  Token.Comment to Color(0xFF8B949E),
  Token.Function to Color(0xFFD2A8FF),
  Token.Type to Color(0xFFFFA657),
  Token.Tag to Color(0xFF7EE787),
)

private val LightPalette = mapOf(
  Token.Plain to Color.Unspecified,
  Token.Keyword to Color(0xFFCF222E),
  Token.String to Color(0xFF0A3069),
  Token.Constant to Color(0xFF0550AE),
  Token.Comment to Color(0xFF6E7781),
  Token.Function to Color(0xFF8250DF),
  Token.Type to Color(0xFF953800),
  Token.Tag to Color(0xFF116329),
)
