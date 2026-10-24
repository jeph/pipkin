package io.jeph.pipkin.ui.chat

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ElapsedTextTest {
  private val locale = Locale.getDefault()

  @Before
  fun english() = Locale.setDefault(Locale.US)

  @After
  fun restore() = Locale.setDefault(locale)

  @Test
  fun showsMinutesWithoutPaddingAndSecondsWithIt() {
    assertEquals("0:07", elapsedText(7_400L))
    assertEquals("1:02", elapsedText(62_000L))
    assertEquals("75:03", elapsedText(4_503_000L))
  }

  @Test
  fun neverGoesBelowZero() = assertEquals("0:00", elapsedText(-5_000L))
}
