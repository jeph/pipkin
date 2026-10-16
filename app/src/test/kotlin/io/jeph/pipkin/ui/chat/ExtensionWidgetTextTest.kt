package io.jeph.pipkin.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import io.jeph.pipkin.ui.theme.LightPipkinColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `ctx.ui.setWidget` lines as the styled text a widget's panel and sheet show. */
class ExtensionWidgetTextTest {
  private val colors = LightPipkinColors

  private fun text(vararg lines: String): AnnotatedString = extensionWidgetText(lines.toList(), colors)

  private fun AnnotatedString.colorAt(index: Int): Color? = spanStyles
    .lastOrNull { index >= it.start && index < it.end && it.item.color != Color.Unspecified }
    ?.item?.color

  @Test
  fun linesKeepTheirSpacesAndBreaks() {
    assertEquals("  ☐ one   two\n\n  ☑ three ", text("  ☐ one   two", "", "  ☑ three ").text)
  }

  @Test
  fun aBreakInsideALineStaysAndOtherControlCharactersGo() {
    assertEquals("a\nb\ncd", text("a\r\nb", "c\u0007d").text)
    assertEquals("ab", text("a\u0000\u001B\u007Fb").text)
  }

  @Test
  fun tabsBecomeSpaces() {
    assertEquals("a   b", text("a\tb").text)
  }

  @Test
  fun styleResetsAtEachLine() {
    val text = text("\u001B[38;5;1mred", "plain")

    assertEquals(colors.destructive, text.colorAt(0))
    assertNull(text.colorAt(4))
  }

  @Test
  fun escapesAreDroppedAndTheirStylesApplied() {
    val text = text("\u001B]8;;https://pi.dev\u0007link\u001B]8;;\u0007 \u001B[2mdim\u001B[22m \u001B[1Gok")

    assertEquals("link dim ok", text.text)
    assertEquals(colors.foregroundExtraMuted, text.colorAt(5))
    assertNull(text.colorAt(9))
  }
}
