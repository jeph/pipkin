package io.jeph.pipkin.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import io.jeph.pipkin.ui.theme.PorcelainColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `ctx.ui.setStatus` texts as the one styled line the header and tab menu show. */
class ExtensionStatusTest {
  private val colors = PorcelainColors

  private fun line(vararg statuses: Pair<String, String>): AnnotatedString? =
    extensionStatusLine(mapOf(*statuses), colors)

  /** The color in effect at [index], or null when the text there has none of its own. */
  private fun AnnotatedString.colorAt(index: Int): Color? = spanStyles
    .lastOrNull { index >= it.start && index < it.end && it.item.color != Color.Unspecified }
    ?.item?.color

  @Test
  fun statusesAreSortedByKeyIgnoringCaseAndJoinedBySpaces() {
    val line = line("ssh" to "SSH: box", "Preset" to "preset:review", "gondolin" to "VM")

    assertEquals("VM preset:review SSH: box", line?.text)
  }

  @Test
  fun eachStatusIsFlattenedToOneLineWithSingleSpacesAndTrimmed() {
    val line = line("a" to "  two\nlines\tand\r\nspaces   here  ", "b" to "next")

    assertEquals("two lines and spaces here next", line?.text)
  }

  @Test
  fun noStatusesOrOnlyBlankOnesGiveNoLine() {
    assertNull(line())
    assertNull(line("a" to "", "b" to " \n\t", "c" to "\u001B[38;5;5m \u001B[39m"))
  }

  @Test
  fun aBlankStatusAddsNoExtraSpaceBetweenOthers() {
    assertEquals("one two", line("a" to "one", "b" to "  ", "c" to "two")?.text)
  }

  @Test
  fun paletteSlotsTakePipkinsRoles() {
    val line = line(
      "1" to "\u001B[38;5;1mE\u001B[39m",
      "2" to "\u001B[38;5;2mS\u001B[39m",
      "3" to "\u001B[38;5;3mW\u001B[39m",
      "4" to "\u001B[38;5;5mA\u001B[39m",
      "5" to "\u001B[95mB\u001B[39m",
      "6" to "\u001B[31mR\u001B[0m",
    )!!

    assertEquals("E S W A B R", line.text)
    assertEquals(colors.destructive, line.colorAt(0))
    assertEquals(colors.dotSuccess, line.colorAt(2))
    assertEquals(colors.dotWarning, line.colorAt(4))
    assertEquals(colors.accent, line.colorAt(6))
    assertEquals(colors.accent, line.colorAt(8))
    assertEquals(colors.destructive, line.colorAt(10))
  }

  @Test
  fun truecolorAndTheColorCubeShowAsSent() {
    val line = line(
      "a" to "\u001B[38;2;228;138;122mT\u001B[39m",
      "b" to "\u001B[38;5;196mC\u001B[39m",
      "c" to "\u001B[38;5;244mG\u001B[39m",
    )!!

    assertEquals(Color(228, 138, 122), line.colorAt(0))
    assertEquals(Color(255, 0, 0), line.colorAt(2))
    assertEquals(Color(128, 128, 128), line.colorAt(4))
  }

  @Test
  fun dimTextIsMutedAndEndsWithItsReset() {
    // Pi's status-line example: an accent dot, then dim text closed by 22;39.
    val line = line("s" to "\u001B[38;5;5m●\u001B[39m\u001B[2m Turn 3...\u001B[22;39m after")!!

    assertEquals("● Turn 3... after", line.text)
    assertEquals(colors.accent, line.colorAt(0))
    assertEquals(colors.foregroundExtraMuted, line.colorAt(2))
    assertNull(line.colorAt(line.text.indexOf("after")))
  }

  @Test
  fun styleDoesNotLeakFromOneStatusIntoTheNext() {
    val line = line("a" to "\u001B[1;38;5;1munclosed", "b" to "plain")!!

    val plain = line.text.indexOf("plain")
    assertNull(line.colorAt(plain))
    assertNull(line.spanStyles.firstOrNull { plain in it.start until it.end && it.item.fontWeight == FontWeight.Bold })
  }

  @Test
  fun backgroundColorsAreSkippedWithoutTheirParametersReadAsCodes() {
    // 48;5;1 must not read as 1 (bold) or 5; 48;2;0;2;3 must not read 2 (dim) or 3 (italic).
    val line = line("a" to "\u001B[48;5;1;38;5;2mA\u001B[48;2;0;2;3mB\u001B[0m")!!

    assertEquals("AB", line.text)
    assertEquals(colors.dotSuccess, line.colorAt(0))
    assertEquals(colors.dotSuccess, line.colorAt(1))
    assertNull(line.spanStyles.firstOrNull { it.item.fontWeight != null || it.item.fontStyle != null })
  }

  @Test
  fun otherEscapesAndControlCharactersAreDropped() {
    val line = line(
      "a" to "\u001B]8;;https://pi.dev\u0007link\u001B]8;;\u0007 \u001B[2K\u001B[1Gclear\u0007\u001B bell",
    )

    assertEquals("link clear bell", line?.text)
  }

  @Test
  fun malformedColorsAreIgnored() {
    val line = line("a" to "\u001B[38;2;300;0mX\u001B[38;5mY\u001B[38;5;999mZ\u001B[mW")!!

    assertEquals("XYZW", line.text)
    assertNull(line.colorAt(3))
  }
}
