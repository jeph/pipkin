package io.jeph.pipkin.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableTest {
  @Test
  fun columnsThatFitOnOneLineTakeOnlyWhatTheyNeed() {
    val widths = columnWidths(intArrayOf(20, 30), intArrayOf(40, 60), available = 400, FLOOR, CAP)
    assertArrayEquals(intArrayOf(40, 60), widths)
  }

  @Test
  fun columnsFillingTheWidthExactlyStayOnOneLine() {
    val widths = columnWidths(intArrayOf(20, 30), intArrayOf(150, 250), available = 400, FLOOR, CAP)
    assertArrayEquals(intArrayOf(150, 250), widths)
  }

  @Test
  fun shortColumnsKeepTheirWidthAndLongOnesShareTheRest() {
    val widths = columnWidths(intArrayOf(60, 30, 70), intArrayOf(100, 40, 900), available = 400, FLOOR, CAP)
    assertArrayEquals(intArrayOf(100, 40, 260), widths)
  }

  @Test
  fun sharedRoomFillsTheWidthExactly() {
    val widths = columnWidths(intArrayOf(10, 10, 10), intArrayOf(300, 301, 302), available = 500, FLOOR, CAP)
    assertEquals(500, widths.sum())
    assertTrue(widths.all { it >= FLOOR })
  }

  @Test
  fun columnsThatCannotFitAtTheirFloorScroll() {
    val widths = columnWidths(IntArray(7) { 50 }, IntArray(7) { 500 }, available = 400, FLOOR, CAP)
    assertArrayEquals(IntArray(7) { FLOOR }, widths)
  }

  @Test
  fun aLongUnbreakableWordHoldsItsColumnOpenOnlyToTheCap() {
    val widths = columnWidths(intArrayOf(1000, 50), intArrayOf(1000, 600), available = 300, FLOOR, CAP)
    assertArrayEquals(intArrayOf(CAP, FLOOR), widths)
  }

  private companion object {
    const val FLOOR = 120
    const val CAP = 200
  }
}
