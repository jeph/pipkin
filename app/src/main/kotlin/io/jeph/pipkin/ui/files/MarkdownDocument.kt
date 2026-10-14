package io.jeph.pipkin.ui.files

/** Whether [name] is a file Paseo previews as markdown: `.md` or `.markdown`, in any case. */
internal fun isMarkdownFile(name: String): Boolean {
  val lower = name.trim().lowercase()
  return lower.endsWith(".md") || lower.endsWith(".markdown")
}

/** A markdown file split as Paseo's preview shows it: its front matter as key and value rows, then the [body]. */
internal data class MarkdownDocument(val frontMatter: List<Pair<String, String>>, val body: String)

/**
 * [source]'s YAML front matter, when it opens with a `---` block of top-level keys, and the markdown after it.
 * Values read as Paseo shows them: scalars unquoted, `~` and empty ones as `null`, lists joined by commas, block
 * scalars joined, and anything nested as its indented YAML. A block that isn't a mapping of distinct keys stays in
 * the body, as Paseo leaves front matter its YAML parser rejects. Line endings come back as `\n`, since the renderer,
 * unlike Paseo's, shows a `\r` as a space.
 */
internal fun parseMarkdownDocument(source: String): MarkdownDocument {
  val text = source.replace(LINE_ENDING, "\n")
  val match = FRONT_MATTER.find(text) ?: return MarkdownDocument(emptyList(), text)
  val rows = frontMatterRows(match.groupValues[1].lines()) ?: return MarkdownDocument(emptyList(), text)
  return MarkdownDocument(rows, text.substring(match.range.last + 1))
}

/** The mapping in [lines] as rows, or null when they aren't a mapping of distinct top-level keys. */
private fun frontMatterRows(lines: List<String>): List<Pair<String, String>>? {
  val rows = mutableListOf<Pair<String, String>>()
  var index = 0
  while (index < lines.size) {
    val line = lines[index]
    index++
    if (line.isBlank() || line.trimStart().startsWith('#')) continue
    val entry = KEY_VALUE.matchEntire(line.trimEnd()) ?: return null
    val key = unquote(entry.groupValues[1].trim())
    if (rows.any { it.first == key }) return null
    // A value may continue on the indented (or, for a list, dash) lines under its key.
    val start = index
    while (index < lines.size &&
      (lines[index].isBlank() || lines[index].startsWith(' ') || lines[index].startsWith('-'))
    ) {
      index++
    }
    val nested = lines.subList(start, index).dropLastWhile { it.isBlank() }
    rows += key to frontMatterValue(entry.groupValues[2].trim(), nested)
  }
  return rows
}

private fun frontMatterValue(inline: String, nested: List<String>): String {
  val block = nested.filter { it.isNotBlank() }
  return when {
    inline == "|" || inline.startsWith("|-") || inline.startsWith("|+") -> dedent(nested).joinToString("\n")

    inline == ">" || inline.startsWith(">-") || inline.startsWith(">+") -> block.joinToString(" ") { it.trim() }

    // A plain scalar may run on over indented lines, which YAML folds into one.
    inline.isNotEmpty() -> scalar((listOf(inline) + block.map { it.trim() }).joinToString(" "))

    block.isEmpty() -> "null"

    block.all { LIST_ITEM.matches(it) } -> block.joinToString(", ") { scalar(it.trim().removePrefix("-").trim()) }

    else -> dedent(nested).joinToString("\n").trim()
  }
}

/** A scalar as Paseo shows it: quotes dropped, `~` as `null`, an inline list joined by commas. */
private fun scalar(raw: String): String {
  val value = withoutComment(raw).trim()
  return when {
    value.isEmpty() || value == "~" || value == "null" -> "null"

    value.startsWith('[') && value.endsWith(']') ->
      value.substring(1, value.length - 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(", ") { unquote(it) }

    else -> unquote(value)
  }
}

/**
 * [raw] up to a YAML comment: a `#` at its start or after whitespace, outside quotes. A quote opens only where a value
 * or inline-list item starts, so the apostrophe in `don't` is plain text; `''` inside single quotes is one quote.
 */
private fun withoutComment(raw: String): String {
  var quote: Char? = null
  var previous: Char? = null
  var index = 0
  while (index < raw.length) {
    val char = raw[index]
    when {
      quote == '"' && char == '\\' -> index++
      quote == '\'' && char == '\'' && raw.getOrNull(index + 1) == '\'' -> index++
      quote != null -> if (char == quote) quote = null
      (char == '"' || char == '\'') && (previous == null || previous == '[' || previous == ',') -> quote = char
      char == '#' && (index == 0 || raw[index - 1].isWhitespace()) -> return raw.substring(0, index)
    }
    if (!char.isWhitespace()) previous = char
    index++
  }
  return raw
}

private fun unquote(value: String): String = when {
  value.length >= 2 && value.startsWith('"') && value.endsWith('"') -> value.substring(1, value.length - 1)

  value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') ->
    value.substring(1, value.length - 1).replace("''", "'")

  else -> value
}

private fun dedent(lines: List<String>): List<String> {
  val indent = lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' }.length } ?: 0
  return lines.map { if (it.isBlank()) "" else it.drop(indent) }
}

/** Paseo's front matter fence: `---` on the first line (after a byte-order mark), up to the next `---` line. */
private val FRONT_MATTER = Regex("^\uFEFF?---[ \t]*\n([\\s\\S]*?)\n---[ \t]*(?:\n|$)")

/** CRLF and lone CR, which markdown reads as line breaks as it does LF. */
private val LINE_ENDING = Regex("\r\n?")

/** A top-level `key: value`, the key unindented and possibly quoted. */
private val KEY_VALUE = Regex("""^("[^"]*"|'[^']*'|[^\s#:'"-][^:]*?):(?:[ \t]+(.*)|)$""")

private val LIST_ITEM = Regex("""^\s*-(\s.*|)$""")
