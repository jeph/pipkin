package io.jeph.pipkin.data

import io.jeph.pipkin.host.SessionInfo
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoArchiveTest {
  private val day = TimeUnit.DAYS.toMillis(1)
  private val now = 100 * day

  private fun session(path: String, modified: Long) = SessionInfo(
    path = path,
    id = path,
    cwd = "/w",
    cwdExists = true,
    name = null,
    created = 0,
    modified = modified,
    messageCount = 1,
    firstMessage = "",
  )

  private fun stale(
    sessions: List<SessionInfo>,
    touched: Map<String, Long> = emptyMap(),
    kept: Set<String> = emptySet(),
    days: Int = 7,
  ) = AutoArchive.stale(sessions, touched, kept, now, days).map { it.path }

  @Test
  fun aChatIsStaleOnceItsLastMessageIsExactlyTheDaysOld() {
    val sessions = listOf(session("due", now - 7 * day), session("early", now - 7 * day + 1))
    assertEquals(listOf("due"), stale(sessions))
  }

  @Test
  fun aLaterTouchOutweighsAnOldMessage() {
    val sessions = listOf(session("seen", now - 20 * day), session("unseen", now - 20 * day))
    assertEquals(listOf("unseen"), stale(sessions, touched = mapOf("seen" to now - day)))
  }

  @Test
  fun anOldTouchDoesNotHideANewMessage() {
    val sessions = listOf(session("active", now - day))
    assertEquals(emptyList<String>(), stale(sessions, touched = mapOf("active" to now - 20 * day)))
  }

  @Test
  fun keptChatsAreNeverStale() {
    val sessions = listOf(session("shown", now - 30 * day), session("old", now - 30 * day))
    assertEquals(listOf("old"), stale(sessions, kept = setOf("shown")))
  }

  @Test
  fun oneDayIsTheShortestWait() {
    val sessions = listOf(session("yesterday", now - day), session("today", now - day + 1))
    assertEquals(listOf("yesterday"), stale(sessions, days = 1))
  }
}
