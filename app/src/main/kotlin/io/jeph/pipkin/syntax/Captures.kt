package io.jeph.pipkin.syntax

/** One node a query captured: chars [start, end) under the query's capture name [capture]. */
internal data class CaptureSpan(val start: Int, val end: Int, val capture: Int)

/** A text predicate on a pattern's match; [texts] gives the text of each node a capture matched. */
internal fun interface Predicate {
  fun test(texts: (capture: Int) -> List<String>): Boolean
}

/**
 * A pattern's predicates from its (type, value id) [steps], [strings] being the query's string table.
 * `#is-not? local` always holds: without a locals query nothing is known to be local, which is how
 * tree-sitter-highlight treats it too. An unknown predicate never holds, leaving its pattern unused.
 */
internal fun parsePredicates(steps: IntArray, strings: List<String>): List<Predicate> {
  val predicates = mutableListOf<Predicate>()
  val arguments = mutableListOf<Argument>()
  for (i in steps.indices step 2) {
    val id = steps[i + 1]
    when (steps[i]) {
      STEP_CAPTURE -> arguments += Argument.Capture(id)

      STEP_STRING -> arguments += Argument.Literal(strings[id])

      STEP_DONE -> {
        predicates += predicate(arguments.toList())
        arguments.clear()
      }
    }
  }
  return predicates
}

/**
 * The spans [matches] (from [TreeSitter.matches]) highlight in [text], dropping matches whose pattern
 * fails one of its [patterns] predicates. Only captures that [paints] count: helpers such as `@_function`
 * or Swift's `@comment @spell` must not displace a colour. Where patterns capture the same range the
 * later pattern wins, as in tree-sitter-highlight. Sorted outermost first: by start, then longest.
 */
internal fun captureSpans(
  text: String,
  matches: IntArray,
  patterns: List<List<Predicate>>,
  paints: (capture: Int) -> Boolean,
): List<CaptureSpan> {
  val byRange = HashMap<Long, Pair<Int, CaptureSpan>>()
  var index = 0
  while (index < matches.size) {
    val pattern = matches[index]
    val captures = (0 until matches[index + 1]).map { i ->
      val at = index + MATCH_HEADER + i * CAPTURE_INTS
      CaptureSpan(start = matches[at + 1], end = matches[at + 2], capture = matches[at])
    }
    index += MATCH_HEADER + captures.size * CAPTURE_INTS
    val texts = { capture: Int -> captures.filter { it.capture == capture }.map { text.substring(it.start, it.end) } }
    if (!patterns[pattern].all { it.test(texts) }) continue
    for (span in captures.filter { paints(it.capture) }) {
      val range = span.start.toLong() shl Int.SIZE_BITS or span.end.toLong()
      val previous = byRange[range]
      if (previous == null || previous.first <= pattern) byRange[range] = pattern to span
    }
  }
  return byRange.values.map { it.second }.sortedWith(compareBy<CaptureSpan> { it.start }.thenByDescending { it.end })
}

/**
 * The edit turning [old] into [new] for [TreeSitter.treeEdit]: everything after their common prefix
 * replaced. Start, old end and new end chars, then each one's row and column, in that order.
 */
internal fun textEdit(old: String, new: String): IntArray {
  // commonPrefixWith never ends inside a surrogate pair, which tree-sitter could not start from.
  val start = old.commonPrefixWith(new).length
  val (startRow, startColumn) = point(new, start)
  val (oldEndRow, oldEndColumn) = point(old, old.length)
  val (newEndRow, newEndColumn) = point(new, new.length)
  return intArrayOf(
    start,
    old.length,
    new.length,
    startRow,
    startColumn,
    oldEndRow,
    oldEndColumn,
    newEndRow,
    newEndColumn,
  )
}

/** The row and column of [offset] in [text]; rows break at `\n`, as tree-sitter counts them. */
private fun point(text: String, offset: Int): Pair<Int, Int> {
  var row = 0
  var lineStart = 0
  for (i in 0 until offset) {
    if (text[i] == '\n') {
      row++
      lineStart = i + 1
    }
  }
  return row to offset - lineStart
}

private sealed interface Argument {
  data class Capture(val index: Int) : Argument

  data class Literal(val value: String) : Argument
}

/** Whether one captured node's [text] passes; [texts] gives other captures' text. */
private typealias NodeTest = (text: String, texts: (capture: Int) -> List<String>) -> Boolean

private fun predicate(arguments: List<Argument>): Predicate {
  val operator = (arguments.firstOrNull() as? Argument.Literal)?.value
  if (operator == "is-not?") return Predicate { true }
  val capture = (arguments.getOrNull(1) as? Argument.Capture)?.index
  val values = arguments.drop(2)
  val test: NodeTest? = when (operator) {
    "eq?", "not-eq?", "any-eq?", "any-not-eq?" -> equalTo(values.singleOrNull())

    // Lua patterns are not regexes, but the one such pattern shipped, "^#!/", reads the same as one.
    "match?", "not-match?", "any-match?", "any-not-match?", "lua-match?" -> matching(values.singleOrNull())

    "any-of?", "not-any-of?" -> oneOf(values)

    else -> null
  }
  if (operator == null || capture == null || test == null) return Predicate { false }
  // Quantified captures need every node to pass, or one for the any- forms (any-of? is not one).
  val negated = operator.startsWith("not-") || operator.startsWith("any-not-")
  val anyNode = operator.startsWith("any-") && operator != "any-of?"
  return Predicate { texts ->
    val nodes = texts(capture)
    if (anyNode) nodes.any { test(it, texts) != negated } else nodes.all { test(it, texts) != negated }
  }
}

private fun equalTo(value: Argument?): NodeTest? = when (value) {
  is Argument.Literal -> { text, _ -> text == value.value }
  is Argument.Capture -> { text, texts -> texts(value.index).firstOrNull() == text }
  null -> null
}

private fun matching(value: Argument?): NodeTest? {
  val pattern = (value as? Argument.Literal)?.value ?: return null
  val regex = runCatching { Regex(pattern) }.getOrNull() ?: return null
  return { text, _ -> regex.containsMatchIn(text) }
}

private fun oneOf(values: List<Argument>): NodeTest? {
  val literals = values.map { (it as? Argument.Literal)?.value ?: return null }.toSet()
  return { text, _ -> text in literals }
}

/** tree-sitter's TSQueryPredicateStepType values. */
private const val STEP_DONE = 0
private const val STEP_CAPTURE = 1
private const val STEP_STRING = 2

/** A match's pattern index and capture count, then each capture's index, start and end. */
private const val MATCH_HEADER = 2
private const val CAPTURE_INTS = 3
