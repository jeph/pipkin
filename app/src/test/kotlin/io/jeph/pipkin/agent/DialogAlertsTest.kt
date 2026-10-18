package io.jeph.pipkin.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogAlertsTest {
  private val events = mutableListOf<String>()
  private val alerts = DialogAlerts<String>(
    post = { chat, dialog -> events += "post $chat ${dialog.id}" },
    cancel = { chat -> events += "cancel $chat" },
  )

  @Test
  fun aDialogInAChatOffScreenIsToldOnce() {
    alerts.update(mapOf("a" to dialog("1"), "b" to null), onScreen = setOf("b"))
    alerts.update(mapOf("a" to dialog("1"), "b" to null), onScreen = setOf("b"))
    alerts.update(mapOf("a" to dialog("1"), "b" to null), onScreen = emptySet())

    assertEquals(listOf("post a 1"), events)
  }

  @Test
  fun aDialogSeenOnScreenIsNotToldAfterLeaving() {
    alerts.update(mapOf("a" to dialog("1")), onScreen = setOf("a"))
    alerts.update(mapOf("a" to dialog("1")), onScreen = emptySet())

    assertEquals(emptyList<String>(), events)
  }

  @Test
  fun comingOnScreenTakesTheNotificationBackForGood() {
    alerts.update(mapOf("a" to dialog("1")), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("1")), onScreen = setOf("a"))
    alerts.update(mapOf("a" to dialog("1")), onScreen = emptySet())

    assertEquals(listOf("post a 1", "cancel a"), events)
  }

  @Test
  fun anAnsweredOrClosedChatsNotificationIsTakenBack() {
    alerts.update(mapOf("a" to dialog("1"), "b" to dialog("2")), onScreen = emptySet())
    alerts.update(mapOf("a" to null), onScreen = emptySet())

    assertEquals(listOf("post a 1", "post b 2"), events.take(2))
    assertEquals(setOf("cancel a", "cancel b"), events.drop(2).toSet())
  }

  @Test
  fun eachDialogReachingTheFrontOffScreenIsTold() {
    alerts.update(mapOf("a" to dialog("1")), onScreen = emptySet())
    // The first expired, so the one queued behind it shows.
    alerts.update(mapOf("a" to dialog("2")), onScreen = emptySet())
    alerts.update(mapOf("a" to null), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("3")), onScreen = emptySet())

    assertEquals(listOf("post a 1", "post a 2", "cancel a", "post a 3"), events)
  }

  @Test
  fun aNewDialogInAChatOnScreenIsNotTold() {
    alerts.update(mapOf("a" to dialog("1")), onScreen = setOf("a"))
    alerts.update(mapOf("a" to dialog("2")), onScreen = setOf("a"))
    alerts.update(mapOf("a" to dialog("2")), onScreen = emptySet())

    assertEquals(emptyList<String>(), events)
  }

  private fun dialog(id: String) = ExtensionUiRequest(
    id = id,
    method = "confirm",
    title = "Run it?",
    message = null,
    options = emptyList(),
    placeholder = null,
    prefill = null,
  )
}
