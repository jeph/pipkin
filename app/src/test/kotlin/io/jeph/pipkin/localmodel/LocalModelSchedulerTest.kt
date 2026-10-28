package io.jeph.pipkin.localmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalModelSchedulerTest {
  private class FakeHost : ModelHost {
    override var loadedModel: String? = null
    val loads = mutableListOf<String>()
    var unloads = 0
    var failNext = false

    override suspend fun load(modelId: String) {
      loads += modelId
      if (failNext) {
        failNext = false
        loadedModel = null
        error("load failed")
      }
      loadedModel = modelId
    }

    override fun unload() {
      unloads += 1
      loadedModel = null
    }
  }

  private val host = FakeHost()
  private val order = mutableListOf<String>()

  private fun TestScope.scheduler(scope: CoroutineScope) = LocalModelScheduler(
    scope,
    host,
    leaseMillis = LEASE,
    idleUnloadMillis = { IDLE },
    now = { testScheduler.currentTime },
  )

  /** Starts a request that records [name] when it runs and holds the model until [release] completes. */
  private fun TestScope.request(
    scheduler: LocalModelScheduler,
    session: String?,
    model: String,
    name: String,
    release: CompletableDeferred<Unit> = CompletableDeferred(Unit),
  ) = async {
    scheduler.run(session, model) {
      order += name
      release.await()
    }
  }

  @Test
  fun `runs one request at a time in arrival order`() = runTest {
    val scheduler = scheduler(backgroundScope)
    val first = CompletableDeferred<Unit>()
    request(scheduler, "a", "m", "a1", first)
    request(scheduler, "b", "m", "b1")
    runCurrent()
    assertEquals(listOf("a1"), order)
    assertEquals(1, scheduler.queue.value.waiting.size)
    first.complete(Unit)
    runCurrent()
    assertEquals(listOf("a1", "b1"), order)
    assertEquals(listOf("m"), host.loads)
  }

  @Test
  fun `swaps the model only after the running request ends`() = runTest {
    val scheduler = scheduler(backgroundScope)
    val first = CompletableDeferred<Unit>()
    request(scheduler, null, "m1", "first", first)
    request(scheduler, null, "m2", "second")
    runCurrent()
    assertEquals(listOf("m1"), host.loads)
    first.complete(Unit)
    runCurrent()
    assertEquals(listOf("m1", "m2"), host.loads)
    assertEquals(listOf("first", "second"), order)
  }

  @Test
  fun `a chat's reservation runs its next request before another chat's model swap`() = runTest {
    val scheduler = scheduler(backgroundScope)
    request(scheduler, "a", "m1", "a1").await()
    request(scheduler, "b", "m2", "b1")
    runCurrent()
    assertEquals("b's swap waits for a's reservation", listOf("a1"), order)

    advanceTimeBy(LEASE / 2)
    request(scheduler, "a", "m1", "a2")
    runCurrent()
    assertEquals(listOf("a1", "a2"), order)
    assertEquals(listOf("m1"), host.loads)

    advanceTimeBy(LEASE + 1)
    runCurrent()
    assertEquals(listOf("a1", "a2", "b1"), order)
    assertEquals(listOf("m1", "m2"), host.loads)
  }

  @Test
  fun `another chat may use the reserved model meanwhile`() = runTest {
    val scheduler = scheduler(backgroundScope)
    request(scheduler, "a", "m1", "a1").await()
    request(scheduler, "b", "m1", "b1")
    runCurrent()
    assertEquals(listOf("a1", "b1"), order)
  }

  @Test
  fun `ending the turn frees the model at once`() = runTest {
    val scheduler = scheduler(backgroundScope)
    request(scheduler, "a", "m1", "a1").await()
    request(scheduler, "b", "m2", "b1")
    runCurrent()
    assertEquals(listOf("a1"), order)
    scheduler.endLease("a")
    runCurrent()
    assertEquals(listOf("a1", "b1"), order)
  }

  @Test
  fun `a request cancelled while waiting leaves the queue`() = runTest {
    val scheduler = scheduler(backgroundScope)
    val first = CompletableDeferred<Unit>()
    request(scheduler, null, "m", "first", first)
    val waiting = request(scheduler, "b", "m", "cancelled")
    request(scheduler, "c", "m", "third")
    runCurrent()
    waiting.cancel()
    runCurrent()
    assertEquals(listOf("c"), scheduler.queue.value.waiting.map { it.session })
    first.complete(Unit)
    runCurrent()
    assertEquals(listOf("first", "third"), order)
  }

  @Test
  fun `a failed load leaves no reservation and lets the next request try`() = runTest {
    val scheduler = scheduler(backgroundScope)
    host.failNext = true
    val failed = async { runCatching { scheduler.run("a", "m1") { order += "never" } } }
    request(scheduler, "b", "m2", "b1")
    runCurrent()
    assertTrue(failed.await().isFailure)
    assertEquals(listOf("b1"), order)
    assertEquals("only b's served request reserves a model", "m2", scheduler.queue.value.reservedModel)
  }

  @Test
  fun `unloads after the idle time with nothing to run`() = runTest {
    val scheduler = scheduler(backgroundScope)
    request(scheduler, null, "m", "only").await()
    advanceTimeBy(IDLE - 1)
    runCurrent()
    assertEquals(0, host.unloads)
    advanceTimeBy(2)
    runCurrent()
    assertEquals(1, host.unloads)
  }

  @Test
  fun `a request arriving before the idle time keeps the model`() = runTest {
    val scheduler = scheduler(backgroundScope)
    request(scheduler, null, "m", "first").await()
    advanceTimeBy(IDLE / 2)
    val held = CompletableDeferred<Unit>()
    request(scheduler, null, "m", "second", held)
    advanceTimeBy(IDLE)
    runCurrent()
    assertEquals(0, host.unloads)
    held.complete(Unit)
    runCurrent()
    assertFalse(scheduler.queue.value.running != null)
  }

  @Test
  fun `picks the reserving chat first, then the reserved model, else waits`() {
    val lease = LocalModelScheduler.Lease("a", "m1", expiresAt = 1)
    val waiting = listOf(QueuedRequest("b", "m2"), QueuedRequest("c", "m1"), QueuedRequest("a", "m1"))
    assertEquals(2, LocalModelScheduler.pickNext(waiting, lease))
    assertEquals(1, LocalModelScheduler.pickNext(waiting.take(2), lease))
    assertNull(LocalModelScheduler.pickNext(waiting.take(1), lease))
    assertEquals(0, LocalModelScheduler.pickNext(waiting, null))
  }

  private companion object {
    const val LEASE = 30_000L
    const val IDLE = 300_000L
  }
}
