package io.jeph.pipkin.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `setWidget` requests as Pi's RPC mode sends them, applied to the widgets a chat shows. */
class ExtensionWidgetTest {
  private fun request(key: String?, lines: List<Any?>?, placement: String? = null) = JSONObject()
    .put("type", "extension_ui_request")
    .put("id", "w")
    .put("method", "setWidget")
    .apply {
      key?.let { put("widgetKey", it) }
      lines?.let { put("widgetLines", JSONArray(it)) }
      placement?.let { put("widgetPlacement", it) }
    }

  private fun Map<String, ExtensionWidget>.set(key: String?, lines: List<Any?>?, placement: String? = null) =
    withWidget(this, request(key, lines, placement))

  @Test
  fun aWidgetGoesAboveTheEditorUnlessPlacedBelow() {
    val widgets = emptyMap<String, ExtensionWidget>()
      .set("plan", listOf("a", "b"))!!
      .set("ci", listOf("c"), "belowEditor")!!
      .set("web", listOf("d"), "aboveEditor")!!

    assertEquals(
      mapOf(
        "plan" to ExtensionWidget(listOf("a", "b"), belowEditor = false),
        "ci" to ExtensionWidget(listOf("c"), belowEditor = true),
        "web" to ExtensionWidget(listOf("d"), belowEditor = false),
      ),
      widgets,
    )
  }

  @Test
  fun noLinesOrAnEmptyListRemovesTheWidget() {
    val widgets = emptyMap<String, ExtensionWidget>().set("a", listOf("1"))!!.set("b", listOf("2"))!!

    assertEquals(listOf("b"), widgets.set("a", null)!!.keys.toList())
    assertEquals(listOf("a"), widgets.set("b", emptyList())!!.keys.toList())
    assertEquals(widgets, widgets.set("gone", null))
  }

  @Test
  fun anUpdateKeepsTheWidgetsPlaceButAMoveToTheOtherEdgeTakesItToTheEnd() {
    val widgets = emptyMap<String, ExtensionWidget>()
      .set("a", listOf("1"))!!
      .set("b", listOf("2"))!!
      .set("c", listOf("3"))!!

    assertEquals(listOf("a", "b", "c"), widgets.set("a", listOf("1 again"))!!.keys.toList())
    assertEquals(listOf("b", "c", "a"), widgets.set("a", listOf("1"), "belowEditor")!!.keys.toList())
  }

  @Test
  fun aLineThatIsNotTextShowsEmpty() {
    val widgets = emptyMap<String, ExtensionWidget>().set("a", listOf("x", 3, JSONObject.NULL))!!

    assertEquals(listOf("x", "", ""), widgets.getValue("a").lines)
  }

  @Test
  fun aRequestWithoutAKeyChangesNothing() {
    assertNull(emptyMap<String, ExtensionWidget>().set(null, listOf("x")))
  }
}
