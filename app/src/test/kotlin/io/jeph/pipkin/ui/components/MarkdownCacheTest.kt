package io.jeph.pipkin.ui.components

import com.mikepenz.markdown.model.State
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownCacheTest {
  private val cache = MarkdownCache()

  /** A finished parse the chat made while on screen; a failed one is kept as well. */
  private fun showed(markdown: String) = cache.put(markdown, State.Error(IllegalStateException(markdown)))

  @Test
  fun warmingParsesTheGivenReplies() {
    runBlocking { cache.warm(setOf("# One", "**two**")) }

    assertTrue(cache["# One"] is State.Success)
    assertTrue(cache["**two**"] is State.Success)
  }

  @Test
  fun aHiddenChatThatKeepsReplyingHoldsOnlyItsLatestWarmedReplies() {
    runBlocking {
      cache.warm(setOf("first reply"))
      cache.warm(setOf("second reply"))
    }

    assertNull(cache["first reply"])
    assertNotNull(cache["second reply"])
  }

  @Test
  fun warmingLeavesRepliesTheChatShowedItself() {
    showed("shown reply")
    runBlocking {
      cache.warm(setOf("shown reply", "warmed reply"))
      cache.warm(setOf("next reply"))
    }

    assertNotNull(cache["shown reply"])
    assertNull(cache["warmed reply"])
  }

  @Test
  fun warmedRepliesCountAsShownOnceTheChatHasLeftTheScreen() {
    runBlocking { cache.warm(setOf("warmed reply")) }
    cache.keepShown(setOf("warmed reply"))
    runBlocking { cache.warm(setOf("next reply")) }

    assertNotNull(cache["warmed reply"])
  }

  @Test
  fun leavingTheScreenDropsRepliesTheChatNoLongerHas() {
    showed("old session")
    showed("kept")

    cache.keepShown(setOf("kept"))

    assertNull(cache["old session"])
    assertNotNull(cache["kept"])
  }

  @Test
  fun onlyAddingAParseChangesTheVersion() {
    val state = State.Error(IllegalStateException())
    val before = cache.version
    cache.put("reply", state)
    val added = cache.version
    cache.put("reply", state)

    assertNotEquals(before, added)
    assertEquals(added, cache.version)
  }
}
