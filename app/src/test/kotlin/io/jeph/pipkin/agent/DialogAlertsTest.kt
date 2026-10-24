package io.jeph.pipkin.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogAlertsTest {
  private val events = mutableListOf<String>()
  private val alerts = DialogAlerts<String>(
    post = { chat, dialog -> events += "post $chat ${dialog.request.id} ${dialog.asker}" },
    retell = { chat, dialog -> events += "retell $chat ${dialog.request.id} ${dialog.asker}" },
    cancel = { chat -> events += "cancel $chat" },
  )

  @Test
  fun aDialogInAChatOffScreenIsToldOnce() {
    alerts.update(mapOf("a" to dialog("1"), "b" to null), onScreen = setOf("b"))
    alerts.update(mapOf("a" to dialog("1"), "b" to null), onScreen = setOf("b"))
    alerts.update(mapOf("a" to dialog("1"), "b" to null), onScreen = emptySet())

    assertEquals(listOf("post a 1 null"), events)
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

    assertEquals(listOf("post a 1 null", "cancel a"), events)
  }

  @Test
  fun anAnsweredOrClosedChatsNotificationIsTakenBack() {
    alerts.update(mapOf("a" to dialog("1"), "b" to dialog("2")), onScreen = emptySet())
    alerts.update(mapOf("a" to null), onScreen = emptySet())

    assertEquals(listOf("post a 1 null", "post b 2 null"), events.take(2))
    assertEquals(setOf("cancel a", "cancel b"), events.drop(2).toSet())
  }

  @Test
  fun eachDialogReachingTheFrontOffScreenIsTold() {
    alerts.update(mapOf("a" to dialog("1")), onScreen = emptySet())
    // The first expired, so the one queued behind it shows.
    alerts.update(mapOf("a" to dialog("2")), onScreen = emptySet())
    alerts.update(mapOf("a" to null), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("3")), onScreen = emptySet())

    assertEquals(listOf("post a 1 null", "post a 2 null", "cancel a", "post a 3 null"), events)
  }

  @Test
  fun aNewDialogInAChatOnScreenIsNotTold() {
    alerts.update(mapOf("a" to dialog("1")), onScreen = setOf("a"))
    alerts.update(mapOf("a" to dialog("2")), onScreen = setOf("a"))
    alerts.update(mapOf("a" to dialog("2")), onScreen = emptySet())

    assertEquals(emptyList<String>(), events)
  }

  @Test
  fun aDialogWhoseAskerIsKnownIsToldWithIt() {
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = emptySet())

    assertEquals(listOf("post a 1 Explore"), events)
  }

  @Test
  fun anAskerNamedAfterTheDialogWasToldIsRetoldOnce() {
    alerts.update(mapOf("a" to dialog("1")), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = emptySet())

    assertEquals(listOf("post a 1 null", "retell a 1 Explore"), events)
  }

  @Test
  fun anotherAskerNamedLaterIsRetold() {
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("1", asker = "General")), onScreen = emptySet())

    assertEquals(listOf("post a 1 Explore", "retell a 1 General"), events)
  }

  @Test
  fun losingTheAskersNameTellsNothing() {
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("1")), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = emptySet())

    assertEquals(listOf("post a 1 Explore"), events)
  }

  @Test
  fun anAskerNamedForADialogNotToldTellsNothing() {
    alerts.update(mapOf("a" to dialog("1")), onScreen = setOf("a"))
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = setOf("a"))
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = emptySet())

    assertEquals(emptyList<String>(), events)
  }

  @Test
  fun anAskerNamedAfterTheNotificationWasTakenBackTellsNothing() {
    alerts.update(mapOf("a" to dialog("1")), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("1")), onScreen = setOf("a"))
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = emptySet())

    assertEquals(listOf("post a 1 null", "cancel a"), events)
  }

  @Test
  fun theNextDialogIsToldAfreshWithItsOwnAsker() {
    alerts.update(mapOf("a" to dialog("1", asker = "Explore")), onScreen = emptySet())
    alerts.update(mapOf("a" to dialog("2")), onScreen = emptySet())

    assertEquals(listOf("post a 1 Explore", "post a 2 null"), events)
  }

  private fun dialog(id: String, asker: String? = null) = FrontDialog(
    request = ExtensionUiRequest(
      id = id,
      method = "confirm",
      title = "Run it?",
      message = null,
      options = emptyList(),
      placeholder = null,
      prefill = null,
    ),
    asker = asker,
  )
}
