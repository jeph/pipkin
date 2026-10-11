package io.jeph.pipkin.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightingTest {
  @Test
  fun fenceNamesAndAliasesPickTheLanguage() {
    assertEquals(SyntaxLanguage.KOTLIN, syntaxLanguage("Kotlin"))
    assertEquals(SyntaxLanguage.KOTLIN, syntaxLanguage("kt"))
    assertEquals(SyntaxLanguage.SHELL, syntaxLanguage("bash"))
    assertEquals(SyntaxLanguage.TYPESCRIPT, syntaxLanguage("tsx title=\"App.tsx\""))
    assertEquals(SyntaxLanguage.DEFAULT, syntaxLanguage("json"))
  }

  @Test
  fun unknownAndGenericFencesStayPlain() {
    assertNull(syntaxLanguage("yaml"))
    assertNull(syntaxLanguage("text"))
    assertNull(syntaxLanguage("default"))
    assertNull(syntaxLanguage(""))
  }

  @Test
  fun everyGrowingPrefixColoursStringsAndCommentsWhereTheyAre() {
    val code = "fun greet() {\n    // Says hello.\n    println(\"Hello\")\n}"
    // Streaming re-highlights each longer prefix; every pass must place its spans on that pass's text.
    for (end in 1..code.length) {
      val text = code.substring(0, end)
      val spans = highlightCode(text, SyntaxLanguage.KOTLIN, dark = true).spanStyles.map {
        text.substring(it.start, it.end) to it.item.color
      }
      if (end == code.length) {
        assertTrue(spans.contains("\"Hello\"" to Color(0xFFA5D6FF)))
        assertTrue(spans.contains("// Says hello." to Color(0xFF8B949E)))
        assertTrue(spans.contains("fun" to Color(0xFFFF7B72)))
      }
    }
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
}
