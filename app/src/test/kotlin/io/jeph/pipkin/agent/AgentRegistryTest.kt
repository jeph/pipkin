package io.jeph.pipkin.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRegistryTest {
  private class FakeProcess(override val cwd: String, sessionPath: String?) : ManagedProcess {
    override val sessionFile = MutableStateFlow(sessionPath)
    override val busy = MutableStateFlow(false)
    var closed = false
    var aborts = 0
    var abortFails = false

    override suspend fun abort() {
      aborts += 1
      if (abortFails) throw RpcError("abort", "Pi did not answer abort in time.")
      busy.value = false
    }

    override fun close() {
      closed = true
    }
  }

  private val created = mutableListOf<FakeProcess>()

  @Test
  fun openReusesTheProcessOfAnOpenSession() = registryTest { registry ->
    val first = registry.open(CWD, "/s/a.jsonl")
    val again = registry.open(CWD, "/s/a.jsonl")
    val fresh = registry.open(CWD, null)

    assertSame(first, again)
    assertNotSame(first, fresh)
    assertEquals(ChatKey("/s/a.jsonl"), registry.keyOf(first))
    assertTrue(registry.keyOf(fresh)?.value.orEmpty().startsWith("new:"))
    assertEquals(2, created.size)
  }

  @Test
  fun newChatIsRekeyedToItsSessionFile() = registryTest { registry ->
    val chat = registry.open(CWD, null)
    registry.setVisible(chat)
    settle()

    chat.sessionFile.value = "/s/new.jsonl"
    settle()

    assertEquals(ChatKey("/s/new.jsonl"), registry.keyOf(chat))
    assertSame(chat, registry.open(CWD, "/s/new.jsonl"))
    assertEquals(setOf(ChatKey("/s/new.jsonl")), registry.processes.value.keys)
  }

  @Test
  fun hiddenIdleProcessesCloseAfterTheIdleDelay() = registryTest(idleCloseMillis = 50) { registry ->
    val hidden = registry.open(CWD, "/s/hidden.jsonl")
    val shown = registry.open(CWD, "/s/shown.jsonl")
    val working = registry.open(CWD, "/s/working.jsonl")
    working.busy.value = true
    registry.setVisible(shown)
    delay(300)

    assertTrue(hidden.closed)
    assertNull(registry.keyOf(hidden))
    assertFalse(shown.closed)
    assertFalse(working.closed)

    working.busy.value = false
    delay(300)

    assertTrue(working.closed)
    assertFalse(shown.closed)
    assertEquals(setOf(ChatKey("/s/shown.jsonl")), registry.processes.value.keys)
  }

  @Test
  fun fifthProcessEvictsTheLeastRecentlyVisibleIdleOne() = registryTest { registry ->
    val processes = List(4) { registry.open(CWD, "/s/$it.jsonl") }
    processes.forEach(registry::setVisible)
    processes[1].busy.value = true

    val fifth = registry.open(CWD, null)

    assertTrue(processes[0].closed)
    assertTrue(processes.drop(1).none { it.closed })
    assertFalse(fifth.closed)
    assertEquals(4, registry.processes.value.size)
    assertSame(processes[3], registry.visible.value)
  }

  @Test
  fun visibleIdleProcessIsEvictedLast() = registryTest { registry ->
    val processes = List(4) { registry.open(CWD, "/s/$it.jsonl") }
    processes.take(3).forEach { it.busy.value = true }
    registry.setVisible(processes[3])

    registry.open(CWD, null)

    assertTrue(processes[3].closed)
    assertNull(registry.visible.value)
  }

  @Test
  fun fifthProcessIsRefusedWhileAllFourWork() = registryTest { registry ->
    val processes = List(4) { registry.open(CWD, "/s/$it.jsonl") }
    processes.forEach { it.busy.value = true }

    assertThrows(TooManyRunning::class.java) { registry.open(CWD, null) }
    assertTrue(processes.none { it.closed })
    assertEquals(4, created.size)
  }

  @Test
  fun reloadReplacesTheProcessUnderItsKeyAndKeepsItVisible() = registryTest { registry ->
    val old = registry.open(CWD, "/s/a.jsonl")
    registry.setVisible(old)

    val replacement = registry.reload(old)

    assertTrue(old.closed)
    assertNotSame(old, replacement)
    assertEquals(CWD, replacement.cwd)
    assertEquals("/s/a.jsonl", replacement.sessionFile.value)
    assertSame(replacement, registry.processes.value[ChatKey("/s/a.jsonl")])
    assertSame(replacement, registry.visible.value)
  }

  @Test
  fun reloadIdleReloadsTheVisibleAndClosesHiddenIdleProcesses() = registryTest { registry ->
    val shown = registry.open(CWD, "/s/shown.jsonl")
    val hidden = registry.open(CWD, "/s/hidden.jsonl")
    val working = registry.open(CWD, "/s/working.jsonl")
    working.busy.value = true
    registry.setVisible(shown)

    registry.reloadIdle()

    assertTrue(shown.closed)
    assertTrue(hidden.closed)
    assertFalse(working.closed)
    val reloaded = registry.visible.value
    assertNotSame(shown, reloaded)
    assertEquals("/s/shown.jsonl", reloaded?.sessionFile?.value)
    assertEquals(setOf(ChatKey("/s/shown.jsonl"), ChatKey("/s/working.jsonl")), registry.processes.value.keys)
  }

  @Test
  fun busyFollowsAnyWorkingProcess() = registryTest { registry ->
    val a = registry.open(CWD, "/s/a.jsonl")
    registry.open(CWD, "/s/b.jsonl")
    settle()
    assertFalse(registry.busy.value)

    a.busy.value = true
    settle()
    assertTrue(registry.busy.value)

    registry.close(a)
    assertFalse(registry.busy.value)
  }

  @Test
  fun abortAllAbortsWorkersAndEndsThoseThatCannotAbort() = registryTest { registry ->
    val worker = registry.open(CWD, "/s/worker.jsonl")
    val stuckHidden = registry.open(CWD, "/s/stuck-hidden.jsonl")
    val stuckShown = registry.open(CWD, "/s/stuck-shown.jsonl")
    val idle = registry.open(CWD, "/s/idle.jsonl")
    listOf(worker, stuckHidden, stuckShown).forEach { it.busy.value = true }
    stuckHidden.abortFails = true
    stuckShown.abortFails = true
    registry.setVisible(stuckShown)

    registry.abortAll()
    settle()

    assertEquals(1, worker.aborts)
    assertFalse(worker.closed)
    assertEquals(0, idle.aborts)
    assertTrue(stuckHidden.closed)
    assertNull(registry.keyOf(stuckHidden))
    assertTrue(stuckShown.closed)
    val restarted = registry.visible.value
    assertNotSame(stuckShown, restarted)
    assertEquals("/s/stuck-shown.jsonl", restarted?.sessionFile?.value)
  }

  private fun registryTest(
    idleCloseMillis: Long = AgentRegistry.IDLE_CLOSE_MILLIS,
    block: suspend (AgentRegistry<FakeProcess>) -> Unit,
  ) = runBlocking {
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    try {
      block(AgentRegistry(scope, idleCloseMillis) { cwd, path -> FakeProcess(cwd, path).also(created::add) })
    } finally {
      scope.cancel()
    }
  }

  /** Lets the registry's watchers react on the test's event loop. */
  private suspend fun settle() = delay(20)

  private companion object {
    const val CWD = "/home"
  }
}
