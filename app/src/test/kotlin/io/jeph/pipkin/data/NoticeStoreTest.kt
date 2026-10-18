package io.jeph.pipkin.data

import io.jeph.pipkin.agent.AppNotice
import io.jeph.pipkin.agent.NoticeLevel
import io.jeph.pipkin.agent.SavedNotice
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoticeStoreTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val now = Executor(Runnable::run)
  private val dir get() = folder.root.resolve("notices")

  private val first = SavedNotice(NoticeLevel.Info, "Sandbox ready", after = null, afterRun = true)
  private val second =
    SavedNotice(NoticeLevel.Error, "Blocked write:\n  .env", after = 1_700_000_000_123, afterRun = false)
  private val own =
    SavedNotice(NoticeLevel.Warning, "", after = 1_700_000_000_456, afterRun = true, AppNotice.ConnectionLost)

  @Test
  fun noticesSurviveARestartPerSession() {
    NoticeStore(dir, now).put("/s/a.jsonl", listOf(first, second, own))

    val restarted = NoticeStore(dir, now)
    assertEquals(listOf(first, second, own), restarted.load("/s/a.jsonl"))
    assertEquals(emptyList<SavedNotice>(), restarted.load("/s/b.jsonl"))
  }

  @Test
  fun noticesSavedBeforePipkinHadNoticesOfItsOwnStillLoad() {
    NoticeStore(dir, now).put("/s/a.jsonl", listOf(first))
    val file = dir.listFiles().orEmpty().single()
    file.writeText("""[{"level":"Info","message":"Sandbox ready","after":null,"afterRun":true}]""")

    assertEquals(listOf(first), NoticeStore(dir, now).load("/s/a.jsonl"))
  }

  @Test
  fun deletedSessionsLoseTheirNoticesAndTheirFiles() {
    val store = NoticeStore(dir, now)
    store.put("/s/a.jsonl", listOf(first))
    store.put("/s/b.jsonl", listOf(second))

    store.delete(listOf("/s/a.jsonl"))

    assertEquals(emptyList<SavedNotice>(), store.load("/s/a.jsonl"))
    val restarted = NoticeStore(dir, now)
    assertEquals(emptyList<SavedNotice>(), restarted.load("/s/a.jsonl"))
    assertEquals(listOf(second), restarted.load("/s/b.jsonl"))
    assertEquals(1, dir.listFiles().orEmpty().size)
  }

  @Test
  fun anUnreadableFileLoadsAsNoNotices() {
    NoticeStore(dir, now).put("/s/a.jsonl", listOf(first))
    val file = dir.listFiles().orEmpty().single()

    file.writeText("""[{"level":"Info","message":"cut""")
    assertEquals(emptyList<SavedNotice>(), NoticeStore(dir, now).load("/s/a.jsonl"))

    file.writeText("""[{"level":"Debug","message":"x","after":null,"afterRun":true}]""")
    assertEquals(emptyList<SavedNotice>(), NoticeStore(dir, now).load("/s/a.jsonl"))
  }

  @Test
  fun theFileEndsWithTheLatestOfManyQuickChanges() {
    val writer = Executors.newSingleThreadExecutor()
    val store = NoticeStore(dir, writer)
    val lists = (1..50).map { count -> List(count) { first.copy(message = "notice $it") } }
    for (notices in lists) store.put("/s/a.jsonl", notices)

    // The cache answers at once, before the writer has caught up.
    assertEquals(lists.last(), store.load("/s/a.jsonl"))
    writer.shutdown()
    assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS))
    assertEquals(lists.last(), NoticeStore(dir, now).load("/s/a.jsonl"))
  }
}
