package io.jeph.pipkin.ui.settings

import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.ModelConfig
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Adding a custom model typed into the add-models search, and which id a lookup's result belongs to. */
class CustomModelLookupTest {
  private val lookup = CustomModelLookup()
  private val added = mutableListOf<String>()

  private suspend fun add(modelId: String, find: suspend (String) -> ModelConfig) =
    lookup.add(modelId, find, fallbackError = "fallback", addKnown = { false }) { added += it.id }

  @Test
  fun `a failure shows for the id that failed, not the search that replaced it`() = runBlocking {
    val reply = CompletableDeferred<ModelConfig>()
    val adding = launch(start = CoroutineStart.UNDISPATCHED) { add("acme/a") { reply.await() } }
    assertEquals("acme/a", lookup.addingId)

    reply.completeExceptionally(IOException("timed out"))
    adding.join()

    assertNull(lookup.errorFor("acme/b"))
    assertEquals("timed out", lookup.errorFor("acme/a"))
    assertNull(lookup.addingId)
    assertEquals(emptyList<String>(), added)
  }

  @Test
  fun `a rejection shows the host's reason, and a failure without one the fallback`() = runBlocking {
    add("acme/a") { throw HostRejected("bad_params", "Unknown model") }
    assertEquals("Unknown model", lookup.errorFor("acme/a"))

    add("acme/b") { throw IOException() }
    assertEquals("fallback", lookup.errorFor("acme/b"))
  }

  @Test
  fun `adding the id again after a failure clears its error`() = runBlocking {
    add("acme/a") { throw IOException("timed out") }
    add("acme/a") { ModelConfig(it) }

    assertNull(lookup.errorFor("acme/a"))
    assertEquals(listOf("acme/a"), added)
  }

  @Test
  fun `a second id is ignored while another lookup runs`() = runBlocking {
    val reply = CompletableDeferred<ModelConfig>()
    val first = launch(start = CoroutineStart.UNDISPATCHED) { add("acme/a") { reply.await() } }

    add("acme/b") { ModelConfig(it) }
    reply.complete(ModelConfig("acme/a"))
    first.join()

    assertEquals(listOf("acme/a"), added)
  }
}
