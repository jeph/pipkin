package io.jeph.pipkin.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.jeph.pipkin.ui.theme.PipkinColors
import java.text.Collator
import java.util.Locale

/** A CSI escape: its parameters and final byte. Only SGR (`m`) styles text; the rest is dropped. */
private val CSI = Regex("\u001B\\[([0-?]*)[ -/]*([@-~])")

/** An OSC escape such as a hyperlink, ended by BEL or ST; dropped. */
private val OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?")

/** The steps of the xterm 256-color cube. */
private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

private const val DIM_ALPHA = 0.7f

/** What a tab in a widget line shows as. */
private const val TAB = "   "

/**
 * The statuses extensions set with `ctx.ui.setStatus` as one line, as Pi's footer shows them: sorted by key, each
 * flattened to one line and trimmed, joined by spaces. Pi's ANSI styling becomes spans: the 16 palette colors Pi's
 * default theme uses map to Pipkin's roles (accent, success, warning, error), other colors show as sent, and dim text is
 * muted. Null when no status has visible text.
 */
internal fun extensionStatusLine(statuses: Map<String, String>, colors: PipkinColors): AnnotatedString? {
  val collator = Collator.getInstance(Locale.ROOT)
  val parts = statuses.entries
    .sortedWith { a, b -> collator.compare(a.key, b.key) }
    .map { styledStatus(it.value, colors) }
    .filter { it.isNotEmpty() }
  if (parts.isEmpty()) return null
  return AnnotatedString.Builder().apply {
    parts.forEachIndexed { index, part ->
      if (index > 0) append(' ')
      append(part)
    }
  }.toAnnotatedString()
}

/**
 * A widget's lines as one text with a line break between them, as Pi's terminal UI draws them: spaces kept, a tab as
 * spaces, and Pi's ANSI styling as spans, as in [extensionStatusLine]. Each line starts unstyled, since Pi's terminal
 * UI resets the style at the end of every line.
 */
internal fun extensionWidgetText(lines: List<String>, colors: PipkinColors): AnnotatedString =
  AnnotatedString.Builder().apply {
    lines.forEachIndexed { index, line ->
      if (index > 0) append('\n')
      forEachStyledRun(line, colors) { run, style ->
        val text = buildString {
          for (char in run) {
            when {
              char == '\t' -> append(TAB)

              char == '\n' -> append(char)

              // Other control characters, such as a lone ESC or a carriage return, would draw as boxes.
              char < ' ' || char == '\u007F' -> Unit

              else -> append(char)
            }
          }
        }
        val at = length
        append(text)
        if (style != null && text.isNotEmpty()) addStyle(style, at, length)
      }
    }
  }.toAnnotatedString()

/** One status: on one line with single spaces and no outer whitespace, as Pi's footer sanitizes it, and styled. */
private fun styledStatus(raw: String, colors: PipkinColors): AnnotatedString {
  val line = OneLine()
  forEachStyledRun(raw, colors, line::append)
  return line.build()
}

/** Calls [onRun] with each run of [raw]'s text and the SGR style over it; other escapes are dropped. */
private inline fun forEachStyledRun(raw: String, colors: PipkinColors, onRun: (String, SpanStyle?) -> Unit) {
  val text = OSC.replace(raw, "")
  val sgr = SgrState(colors)
  var start = 0
  for (escape in CSI.findAll(text)) {
    onRun(text.substring(start, escape.range.first), sgr.style())
    if (escape.groupValues[2] == "m") sgr.apply(escape.groupValues[1])
    start = escape.range.last + 1
  }
  onRun(text.substring(start), sgr.style())
}

/** Styled runs on one line: line breaks and tabs become spaces, runs of spaces one, and none at either end. */
private class OneLine {
  private val builder = AnnotatedString.Builder()

  /** True at the start too, so leading spaces are dropped. */
  private var endsWithSpace = true

  fun append(run: String, style: SpanStyle?) {
    val text = buildString {
      for (char in run) {
        val shown = if (char == '\n' || char == '\r' || char == '\t') ' ' else char
        // Other control characters, such as a lone ESC, would draw as boxes.
        if (shown < ' ' || shown == '\u007F' || (shown == ' ' && endsWithSpace)) continue
        append(shown)
        endsWithSpace = shown == ' '
      }
    }
    if (text.isEmpty()) return
    val at = builder.length
    builder.append(text)
    if (style != null) builder.addStyle(style, at, builder.length)
  }

  fun build(): AnnotatedString {
    val line = builder.toAnnotatedString()
    return if (endsWithSpace && line.isNotEmpty()) line.subSequence(0, line.length - 1) else line
  }
}

/** The SGR attributes in effect while reading one status or widget line. */
private class SgrState(private val colors: PipkinColors) {
  private var foreground: Color? = null
  private var dim = false
  private var bold = false
  private var italic = false
  private var underline = false
  private var strikethrough = false

  fun style(): SpanStyle? {
    val foreground = foreground
    val color = when {
      foreground != null && dim -> foreground.copy(alpha = foreground.alpha * DIM_ALPHA)
      foreground != null -> foreground
      dim -> colors.foregroundExtraMuted
      else -> null
    }
    val decorations = listOfNotNull(
      TextDecoration.Underline.takeIf { underline },
      TextDecoration.LineThrough.takeIf { strikethrough },
    )
    if (color == null && !bold && !italic && decorations.isEmpty()) return null
    return SpanStyle(
      color = color ?: Color.Unspecified,
      fontWeight = FontWeight.Bold.takeIf { bold },
      fontStyle = FontStyle.Italic.takeIf { italic },
      textDecoration = decorations.takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
    )
  }

  /** Applies one SGR escape's `;`-separated parameters; an empty list or parameter is 0, a reset. */
  fun apply(parameters: String) {
    val codes = parameters.split(';').map { it.toIntOrNull() ?: 0 }
    var i = 0
    while (i < codes.size) {
      i += applyCode(codes, i)
    }
  }

  /** Applies the code at [i] and returns how many parameters it took. */
  private fun applyCode(codes: List<Int>, i: Int): Int {
    when (val code = codes[i]) {
      0 -> reset()

      1 -> bold = true

      2 -> dim = true

      3 -> italic = true

      4 -> underline = true

      9 -> strikethrough = true

      22 -> {
        bold = false
        dim = false
      }

      23 -> italic = false

      24 -> underline = false

      29 -> strikethrough = false

      in 30..37 -> foreground = paletteColor(code - 30)

      in 90..97 -> foreground = paletteColor(code - 90 + 8)

      39 -> foreground = null

      38 -> return extendedColor(codes, i) { foreground = it }

      // Backgrounds aren't shown, but a 48 color's parameters must not be read as codes.
      48 -> return extendedColor(codes, i) {}
    }
    return 1
  }

  /** Reads `38;5;n` or `38;2;r;g;b` starting at [i] into [set]; returns the parameters it took. */
  private inline fun extendedColor(codes: List<Int>, i: Int, set: (Color?) -> Unit): Int =
    when (codes.getOrNull(i + 1)) {
      5 -> {
        codes.getOrNull(i + 2)?.let { set(paletteColor(it)) }
        3
      }

      2 -> {
        val rgb = codes.subList((i + 2).coerceAtMost(codes.size), (i + 5).coerceAtMost(codes.size))
        if (rgb.size == 3) set(Color(rgb[0].coerceIn(0, 255), rgb[1].coerceIn(0, 255), rgb[2].coerceIn(0, 255)))
        5
      }

      else -> 1
    }

  /** Palette slots 0-15 by the role Pi's default theme gives them; 16-255 as xterm defines them. */
  private fun paletteColor(index: Int): Color? = when (index) {
    in 0..15 -> when (index % 8) {
      1 -> colors.destructive
      2 -> colors.dotSuccess
      3 -> colors.dotWarning
      4, 5, 6 -> colors.accent
      7 -> colors.foreground
      else -> colors.foregroundMuted
    }

    in 16..231 -> (index - 16).let { Color(CUBE[it / 36], CUBE[it / 6 % 6], CUBE[it % 6]) }

    in 232..255 -> (8 + (index - 232) * 10).let { Color(it, it, it) }

    else -> null
  }

  private fun reset() {
    foreground = null
    dim = false
    bold = false
    italic = false
    underline = false
    strikethrough = false
  }
}
