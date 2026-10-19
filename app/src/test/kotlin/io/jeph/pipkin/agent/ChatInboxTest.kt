package io.jeph.pipkin.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInboxTest {
  private val inbox = ChatInbox()

  @Test
  fun aChatOnScreenInAnyPaneIsNotAdded() {
    inbox.setOnScreen(setOf("left", "right"))

    inbox.add(entry("left", InboxReason.Finished))
    inbox.add(entry("right", InboxReason.Waiting))
    inbox.add(entry("hidden", InboxReason.Finished))

    assertEquals(listOf("hidden"), paths())
  }

  @Test
  fun newestComesFirstAndAChatKeepsOneEntry() {
    inbox.add(entry("a", InboxReason.Waiting, at = 1))
    inbox.add(entry("b", InboxReason.Finished, at = 2))
    inbox.add(entry("a", InboxReason.Finished, at = 3))

    assertEquals(listOf("a" to InboxReason.Finished, "b" to InboxReason.Finished), contents())
  }

  @Test
  fun comingOnScreenTakesTheChatOutAndKeepsItOutWhileShown() {
    inbox.add(entry("a", InboxReason.Finished))
    inbox.add(entry("b", InboxReason.Finished))

    inbox.setOnScreen(setOf("a"))
    inbox.add(entry("a", InboxReason.Waiting))

    assertEquals(listOf("b"), paths())
  }

  @Test
  fun leavingAChatLetsItBeAddedAgain() {
    inbox.setOnScreen(setOf("a"))
    inbox.setOnScreen(emptySet())

    inbox.add(entry("a", InboxReason.Finished))

    assertEquals(listOf("a"), paths())
  }

  @Test
  fun aDialogGoingAwayTakesOnlyItsWaitingEntryOut() {
    inbox.add(entry("waiting", InboxReason.Waiting))
    inbox.add(entry("finished", InboxReason.Finished))

    inbox.dialogGone("waiting")
    inbox.dialogGone("finished")

    assertEquals(listOf("finished"), paths())
  }

  @Test
  fun openingOneLeavesTheOthers() {
    inbox.add(entry("a", InboxReason.Finished))
    inbox.add(entry("b", InboxReason.Waiting))

    inbox.remove("b")

    assertEquals(listOf("a"), paths())
  }

  @Test
  fun clearingEmptiesIt() {
    inbox.add(entry("a", InboxReason.Finished))
    inbox.add(entry("b", InboxReason.Failed))

    inbox.clear()

    assertEquals(emptyList<String>(), paths())
  }

  @Test
  fun switchedOffItDropsWhatItHeldAndTakesNothingIn() {
    inbox.add(entry("a", InboxReason.Finished))

    inbox.enabled = false
    inbox.add(entry("b", InboxReason.Waiting))

    assertEquals(emptyList<String>(), paths())

    inbox.enabled = true
    inbox.add(entry("c", InboxReason.Exited))

    assertEquals(listOf("c"), paths())
  }

  @Test
  fun archivingTakesTheChatOutAndKeepsItsStoppedRunOut() {
    inbox.add(entry("archived", InboxReason.Finished))
    inbox.add(entry("kept", InboxReason.Finished))

    inbox.setArchived(setOf("archived"))
    inbox.add(entry("archived", InboxReason.Exited))

    assertEquals(listOf("kept"), paths())
  }

  @Test
  fun unarchivingLetsItBeAddedAgain() {
    inbox.setArchived(setOf("chat"))
    inbox.setArchived(emptySet())

    inbox.add(entry("chat", InboxReason.Finished))

    assertEquals(listOf("chat"), paths())
  }

  private fun paths() = inbox.entries.value.map { it.location.sessionPath }

  private fun contents() = inbox.entries.value.map { it.location.sessionPath to it.reason }

  private fun entry(path: String, reason: InboxReason, at: Long = 0) =
    InboxEntry(ChatLocation("/cwd", path), reason, title = null, detail = null, at = at)
}
