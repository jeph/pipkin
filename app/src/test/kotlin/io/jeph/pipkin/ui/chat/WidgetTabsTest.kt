package io.jeph.pipkin.ui.chat

import io.jeph.pipkin.agent.ExtensionWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which widget tabs are open and which mark a change, as widgets come, change, move and go. */
class WidgetTabsTest {
  private val tabs = WidgetTabs()

  private fun above(vararg lines: String) = ExtensionWidget(lines.toList(), belowEditor = false)

  private fun below(vararg lines: String) = ExtensionWidget(lines.toList(), belowEditor = true)

  @Test
  fun aNewWidgetIsSeenAndOnlyItsLaterChangesMarkIt() {
    tabs.track(mapOf("plan" to above("1")))
    assertFalse(tabs.changed("plan", above("1")))

    tabs.track(mapOf("plan" to above("2")))
    assertTrue(tabs.changed("plan", above("2")))

    tabs.see("plan", above("2"))
    assertFalse(tabs.changed("plan", above("2")))
  }

  @Test
  fun aWidgetThatComesBackIsNew() {
    tabs.track(mapOf("plan" to above("1")))
    tabs.track(emptyMap())
    tabs.track(mapOf("plan" to above("2")))

    assertFalse(tabs.changed("plan", above("2")))
  }

  @Test
  fun eachEdgeHasOneOpenTabAndTappingItAgainClosesIt() {
    tabs.toggle("plan", belowEditor = false)
    tabs.toggle("ci", belowEditor = true)
    tabs.toggle("web", belowEditor = false)

    assertEquals("web", tabs.open(belowEditor = false))
    assertEquals("ci", tabs.open(belowEditor = true))

    tabs.toggle("web", belowEditor = false)
    assertNull(tabs.open(belowEditor = false))
  }

  @Test
  fun aTabClosesWhenItsWidgetGoesOrMovesToTheOtherEdge() {
    tabs.toggle("plan", belowEditor = false)
    tabs.toggle("ci", belowEditor = true)

    tabs.track(mapOf("plan" to below("1"), "ci" to below("2")))
    assertNull(tabs.open(belowEditor = false))
    assertEquals("ci", tabs.open(belowEditor = true))

    tabs.track(mapOf("plan" to below("1")))
    assertNull(tabs.open(belowEditor = true))
  }

  @Test
  fun labelsComeFromTheKey() {
    assertEquals("Plan todos", widgetLabel("plan-todos"))
    assertEquals("Web activity", widgetLabel("web_activity"))
    assertEquals("-", widgetLabel("-"))
  }
}
