package io.jeph.pipkin.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatSteeringTest {
  private fun steer(typed: String) = SentSteer(typed, listOf(Attachment.File("$typed.txt", ByteArray(0))))

  @Test
  fun `steers queued under the same text keep their own attachments`() {
    val firstPhoto = steer("")
    val message = steer("look")
    val secondPhoto = steer("")
    val sent = mapOf("" to listOf(firstPhoto, secondPhoto), "look" to listOf(message))

    val matched = matchSteers(listOf("", "look", ""), sent)

    assertEquals(3, matched.size)
    assertSame(firstPhoto, matched[0])
    assertSame(message, matched[1])
    assertSame(secondPhoto, matched[2])
  }

  @Test
  fun `the steers still queued under a text are the newest sent under it`() {
    val delivered = steer("")
    val queued = steer("")

    val matched = matchSteers(listOf(""), mapOf("" to listOf(delivered, queued)))

    assertSame(queued, matched.single())
  }

  @Test
  fun `a queued message Pipkin did not send counts as typed without attachments`() {
    val matched = matchSteers(listOf("from an extension"), emptyMap()).single()

    assertEquals("from an extension", matched.typed)
    assertEquals(emptyList<Attachment>(), matched.picked)
  }
}
