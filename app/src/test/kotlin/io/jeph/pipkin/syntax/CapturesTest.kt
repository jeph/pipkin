package io.jeph.pipkin.syntax

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturesTest {
  @Test
  fun theLaterPatternWinsARangeAndOuterSpansComeFirst() {
    val text = "foo(bar)"
    val matches = intArrayOf(
      // pattern 1 calls foo a function, pattern 0 a variable, and pattern 0 also covers the call.
      1, 1, FUNCTION, 0, 3,
      0, 1, VARIABLE, 0, 3,
      0, 1, CALL, 0, 8,
      0, 1, VARIABLE, 4, 7,
    )

    val spans = captureSpans(text, matches, List(2) { emptyList() }) { true }

    assertEquals(
      listOf(CaptureSpan(0, 8, CALL), CaptureSpan(0, 3, FUNCTION), CaptureSpan(4, 7, VARIABLE)),
      spans,
    )
  }

  @Test
  fun aCaptureThatPaintsNothingLeavesTheRangeToOneThatDoes() {
    // Swift's `(comment) @comment @spell`, and a later Kotlin pattern's `@_function` helper.
    val matches = intArrayOf(
      0, 2, COMMENT, 0, 4, SPELL, 0, 4,
      0, 1, FUNCTION, 5, 8,
      1, 1, SPELL, 5, 8,
    )

    val spans = captureSpans("// a foo", matches, List(2) { emptyList() }) { it != SPELL }

    assertEquals(listOf(CaptureSpan(0, 4, COMMENT), CaptureSpan(5, 8, FUNCTION)), spans)
  }

  @Test
  fun aMatchFailingItsPredicatesCapturesNothing() {
    // (identifier) @constant (#match? @constant "^[A-Z]")
    val steps = intArrayOf(STRING, 0, CAPTURE, CONSTANT, STRING, 1, DONE, 0)
    val patterns = listOf(parsePredicates(steps, listOf("match?", "^[A-Z]")))
    val matches = intArrayOf(0, 1, CONSTANT, 0, 3, 0, 1, CONSTANT, 4, 7)

    val spans = captureSpans("MAX min", matches, patterns) { true }

    assertEquals(listOf(CaptureSpan(0, 3, CONSTANT)), spans)
  }

  @Test
  fun textPredicatesCompareCapturedText() {
    val strings = listOf("eq?", "require", "any-of?", "self", "static", "not-eq?", "not-any-of?")

    fun holds(steps: IntArray, vararg texts: Pair<Int, String>): Boolean {
      val byCapture = texts.groupBy({ it.first }, { it.second })
      return parsePredicates(steps, strings).all { it.test { capture -> byCapture[capture].orEmpty() } }
    }

    val eqLiteral = intArrayOf(STRING, 0, CAPTURE, 0, STRING, 1, DONE, 0)
    assertTrue(holds(eqLiteral, 0 to "require"))
    assertFalse(holds(eqLiteral, 0 to "required"))

    val eqCapture = intArrayOf(STRING, 0, CAPTURE, 0, CAPTURE, 1, DONE, 0)
    assertTrue(holds(eqCapture, 0 to "a", 1 to "a"))
    assertFalse(holds(eqCapture, 0 to "a", 1 to "b"))

    val notEq = intArrayOf(STRING, 5, CAPTURE, 0, STRING, 1, DONE, 0)
    assertFalse(holds(notEq, 0 to "require"))

    val anyOf = intArrayOf(STRING, 2, CAPTURE, 0, STRING, 3, STRING, 4, DONE, 0)
    assertTrue(holds(anyOf, 0 to "static"))
    assertFalse(holds(anyOf, 0 to "this"))

    val notAnyOf = intArrayOf(STRING, 6, CAPTURE, 0, STRING, 3, STRING, 4, DONE, 0)
    assertTrue(holds(notAnyOf, 0 to "this"))

    // A quantified capture needs every node to pass.
    assertFalse(holds(eqLiteral, 0 to "require", 0 to "x"))
    // Two predicates must both hold.
    assertFalse(holds(eqLiteral + anyOf, 0 to "require"))
  }

  @Test
  fun isNotLocalHoldsAndUnknownPredicatesDisableThePattern() {
    val strings = listOf("is-not?", "local", "unknown?")

    val isNotLocal = parsePredicates(intArrayOf(STRING, 0, STRING, 1, DONE, 0), strings).single()
    val unknown = parsePredicates(intArrayOf(STRING, 2, CAPTURE, 0, DONE, 0), strings).single()

    assertTrue(isNotLocal.test { listOf("document") })
    assertFalse(unknown.test { listOf("document") })
  }

  @Test
  fun anAppendEditsFromTheOldEnd() {
    val edit = textEdit("val x\nval", "val x\nval y = 1")

    assertArrayEquals(intArrayOf(9, 9, 15, 1, 3, 1, 3, 1, 9), edit)
  }

  @Test
  fun aChangeInsideReplacesEverythingAfterTheCommonPrefix() {
    val edit = textEdit("a\nbc\nd", "a\nbX\nd\ne")

    assertArrayEquals(intArrayOf(3, 6, 8, 1, 1, 2, 1, 3, 1), edit)
  }

  @Test
  fun anEditNeverStartsInsideASurrogatePair() {
    // U+1F600 and U+1F601 share their high surrogate.
    val edit = textEdit("x\uD83D\uDE00", "x\uD83D\uDE01")

    assertEquals(1, edit[0])
  }

  private companion object {
    const val DONE = 0
    const val CAPTURE = 1
    const val STRING = 2

    const val VARIABLE = 0
    const val FUNCTION = 1
    const val CALL = 2
    const val CONSTANT = 0
    const val COMMENT = 3
    const val SPELL = 4
  }
}
