package io.jeph.pipkin.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.jeph.pipkin.syntax.CaptureSpan
import io.jeph.pipkin.syntax.Grammar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeHighlightingTest {
  @Test
  fun fenceNamesAndAliasesPickTheGrammar() {
    assertEquals(Grammar.KOTLIN, Grammar.forName("Kotlin"))
    assertEquals(Grammar.KOTLIN, Grammar.forName("kt"))
    assertEquals(Grammar.BASH, Grammar.forName("sh"))
    assertEquals(Grammar.TSX, Grammar.forName("tsx title=\"App.tsx\""))
    assertEquals(Grammar.XML, Grammar.forName("xml"))
  }

  @Test
  fun unknownFencesStayPlain() {
    assertNull(Grammar.forName("yaml"))
    assertNull(Grammar.forName("text"))
    assertNull(Grammar.forName(""))
  }

  @Test
  fun aCaptureTakesItsNearestNamedAncestorsToken() {
    assertEquals(Token.Function, tokenFor("function.method.builtin"))
    assertEquals(Token.Constant, tokenFor("tag.attribute"))
    assertEquals(Token.Tag, tokenFor("tag.builtin"))
    assertEquals(Token.Constant, tokenFor("string.special.key"))
    assertNull(tokenFor("_function"))
    assertNull(tokenFor("spell"))
  }

  @Test
  fun aFilesExtensionPicksItsLanguage() {
    assertEquals("kt", fileLanguage("app/src/Main.kt"))
    assertEquals("ts", fileLanguage("/home/u/.config/app/index.d.ts"))
    assertNull(fileLanguage("/home/u/.config/Makefile"))
    assertNull(fileLanguage("notes."))
  }

  @Test
  fun splitLinesKeepsEachSpanOnItsLine() {
    val red = SpanStyle(color = Color.Red)
    val text = buildAnnotatedString {
      append("a ")
      withStyle(red) { append("\"b\r\nc\"") }
      append("\rd\n")
    }

    val lines = text.splitLines()

    assertEquals(listOf("a \"b", "c\"", "d", ""), lines.map { it.text })
    assertEquals(listOf(AnnotatedString.Range(red, 2, 4)), lines[0].spanStyles)
    assertEquals(listOf(AnnotatedString.Range(red, 0, 2)), lines[1].spanStyles)
    assertEquals(emptyList<AnnotatedString.Range<SpanStyle>>(), lines[2].spanStyles)
  }

  @Test
  fun innerCapturesRepaintTheirPartOfAnOuterOne() {
    // `${x}` inside a template string: the substitution goes plain, its `${` keyword-coloured.
    val text = "`a \${x}`"
    val spans =
      listOf(CaptureSpan(0, 8, STRING), CaptureSpan(3, 7, PLAIN), CaptureSpan(3, 5, KEYWORD), CaptureSpan(5, 6, HELPER))
    val colors = mapOf(STRING to Color.Blue, PLAIN to Color.Unspecified, KEYWORD to Color.Red)

    val painted = paint(text, spans) { colors[it] }

    assertEquals(
      listOf(
        AnnotatedString.Range(SpanStyle(color = Color.Blue), 0, 3),
        AnnotatedString.Range(SpanStyle(color = Color.Red), 3, 5),
        AnnotatedString.Range(SpanStyle(color = Color.Blue), 7, 8),
      ),
      painted.spanStyles,
    )
  }

  @Test
  fun spansPastAShorterTextAreClipped() {
    val painted = paint("ab", listOf(CaptureSpan(1, 5, STRING))) { Color.Blue }

    assertEquals(listOf(AnnotatedString.Range(SpanStyle(color = Color.Blue), 1, 2)), painted.spanStyles)
  }

  @Test
  fun aGrowingBlockKeepsItsColoursAndAddsThePlainRest() {
    val red = SpanStyle(color = Color.Red)
    val highlighted = buildAnnotatedString {
      withStyle(red) { append("val") }
      append(" x")
    }

    val grown = withPlainTail(highlighted, "val x = 1")

    assertEquals("val x = 1", grown.text)
    assertEquals(listOf(AnnotatedString.Range(red, 0, 3)), grown.spanStyles)
    assertEquals(highlighted, withPlainTail(highlighted, "val x"))
  }

  @Test
  fun anEditedOrMissingHighlightFallsBackToPlainText() {
    val highlighted = buildAnnotatedString { withStyle(SpanStyle(color = Color.Red)) { append("val x") } }

    assertEquals(AnnotatedString("var y"), withPlainTail(highlighted, "var y"))
    assertEquals(AnnotatedString("val"), withPlainTail(highlighted, "val"))
    assertEquals(AnnotatedString("val"), withPlainTail(null, "val"))
  }

  private companion object {
    const val STRING = 0
    const val PLAIN = 1
    const val KEYWORD = 2
    const val HELPER = 3
  }
}
