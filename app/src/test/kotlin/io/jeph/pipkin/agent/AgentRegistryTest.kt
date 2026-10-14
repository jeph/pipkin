package io.jeph.pipkin.agent

import io.jeph.pipkin.workspace.Pane
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
    var parked = false
    var restarts = 0
    var aborts = 0
    var abortFails = false

    override suspend fun abort() {
      aborts += 1
      if (abortFails) throw RpcError("abort", "The agent did not answer abort in time.")
      busy.value = false
    }

    override fun park() {
      parked = true
    }

    override fun restart() {
      parked = false
      restarts += 1
    }

    override fun close() {
      closed = true
    }
  }

  private val created = mutableListOf<FakeProcess>()
  private val ended = mutableListOf<FakeProcess>()

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
    registry.show(Pane.Primary, chat)
    settle()

    chat.sessionFile.value = "/s/new.jsonl"
    settle()

    assertEquals(ChatKey("/s/new.jsonl"), registry.keyOf(chat))
    assertSame(chat, registry.open(CWD, "/s/new.jsonl"))
    assertEquals(setOf(ChatKey("/s/new.jsonl")), registry.processes.value.keys)
  }

  @Test
  fun hiddenIdleProcessesParkAfterTheIdleDelay() = registryTest(idleCloseMillis = 50) { registry ->
    val hidden = registry.open(CWD, "/s/hidden.jsonl")
    val shown = registry.open(CWD, "/s/shown.jsonl")
    val split = registry.open(CWD, "/s/split.jsonl")
    val working = registry.open(CWD, "/s/working.jsonl")
    working.busy.value = true
    registry.show(Pane.Primary, shown)
    registry.show(Pane.Secondary, split)
    delay(300)

    assertTrue(hidden.parked)
    assertFalse(hidden.closed)
    assertNull(registry.keyOf(hidden))
    assertFalse(shown.parked)
    assertFalse(split.parked)
    assertFalse(working.parked)

    working.busy.value = false
    registry.show(Pane.Secondary, null)
    delay(300)

    assertTrue(working.parked)
    assertTrue(split.parked)
    assertFalse(shown.parked)
    assertEquals(setOf(ChatKey("/s/shown.jsonl")), registry.processes.value.keys)
  }

  @Test
  fun aProcessShowsInOnePaneAtATime() = registryTest { registry ->
    val a = registry.open(CWD, "/s/a.jsonl")
    val b = registry.open(CWD, "/s/b.jsonl")
    registry.show(Pane.Primary, a)
    registry.show(Pane.Secondary, b)

    assertFalse(registry.show(Pane.Secondary, a))
    assertEquals(mapOf(Pane.Primary to a, Pane.Secondary to b), registry.shown.value)

    registry.close(b)

    assertEquals(mapOf(Pane.Primary to a), registry.shown.value)
    registry.show(Pane.Primary, null)
    assertTrue(registry.show(Pane.Secondary, a))
    assertEquals(Pane.Secondary, registry.paneOf(a))
  }

  @Test
  fun reopeningAParkedSessionRestartsTheSameChat() = registryTest { registry ->
    val chat = registry.open(CWD, "/s/a.jsonl")
    registry.reloadIdle()

    val reopened = registry.open(CWD, "/s/a.jsonl")

    assertSame(chat, reopened)
    assertFalse(chat.parked)
    assertEquals(1, chat.restarts)
    assertEquals(ChatKey("/s/a.jsonl"), registry.keyOf(chat))
    assertEquals(1, created.size)
  }

  @Test
  fun discardedParkedChatClosesAndReopensFresh() = registryTest { registry ->
    val chat = registry.open(CWD, "/s/a.jsonl")
    registry.reloadIdle()

    registry.discardParked(listOf("/s/a.jsonl", "/s/never-opened.jsonl"))

    assertTrue(chat.closed)
    assertNotSame(chat, registry.open(CWD, "/s/a.jsonl"))
  }

  @Test
  fun newChatWithoutASessionFileClosesInsteadOfParking() = registryTest { registry ->
    val chat = registry.open(CWD, null)

    registry.reloadIdle()

    assertTrue(chat.closed)
    assertFalse(chat.parked)
  }

  @Test
  fun parkingPastTheLimitClosesTheLeastRecentlyParked() = registryTest { registry ->
    val chats = List(AgentRegistry.MAX_PARKED) { registry.open(CWD, "/s/$it.jsonl").also { registry.reloadIdle() } }
    // Reopened and parked again, the first chat becomes the most recently parked.
    registry.open(CWD, "/s/0.jsonl")
    registry.reloadIdle()

    registry.open(CWD, "/s/extra.jsonl")
    registry.reloadIdle()

    assertTrue(chats[1].closed)
    assertTrue(chats.filter { it !== chats[1] }.none { it.closed })
    assertSame(chats[0], registry.open(CWD, "/s/0.jsonl"))
  }

  @Test
  fun refusedReopenKeepsTheChatParked() = registryTest { registry ->
    val parkedChat = registry.open(CWD, "/s/parked.jsonl")
    registry.reloadIdle()
    val workers = List(4) { registry.open(CWD, "/s/w$it.jsonl") }
    workers.forEach { it.busy.value = true }

    assertThrows(TooManyRunning::class.java) { registry.open(CWD, "/s/parked.jsonl") }
    assertEquals(0, parkedChat.restarts)

    workers[0].busy.value = false
    assertSame(parkedChat, registry.open(CWD, "/s/parked.jsonl"))
    assertTrue(workers[0].parked)
  }

  @Test
  fun fifthProcessEvictsTheLeastRecentlyVisibleIdleOne() = registryTest { registry ->
    val processes = List(4) { registry.open(CWD, "/s/$it.jsonl") }
    processes.forEach { registry.show(Pane.Primary, it) }
    processes[1].busy.value = true

    val fifth = registry.open(CWD, null)

    assertTrue(processes[0].parked)
    assertTrue(processes.drop(1).none { it.parked || it.closed })
    assertFalse(fifth.closed)
    assertEquals(4, registry.processes.value.size)
    assertSame(processes[3], registry.shown.value[Pane.Primary])
  }

  @Test
  fun shownIdleProcessesAreNeverEvicted() = registryTest { registry ->
    val main = registry.open(CWD, "/s/main.jsonl")
    val split = registry.open(CWD, "/s/split.jsonl")
    val hidden = registry.open(CWD, "/s/hidden.jsonl")
    val working = registry.open(CWD, "/s/working.jsonl")
    working.busy.value = true
    registry.show(Pane.Primary, hidden)
    registry.show(Pane.Primary, main)
    registry.show(Pane.Secondary, split)

    registry.open(CWD, null)

    assertTrue(hidden.parked)
    assertFalse(main.parked || split.parked)
    assertEquals(mapOf(Pane.Primary to main, Pane.Secondary to split), registry.shown.value)

    registry.processes.value.values.filter { it !== main && it !== split }.forEach { it.busy.value = true }
    assertThrows(TooManyRunning::class.java) { registry.open(CWD, null) }
    assertFalse(main.parked || split.parked)
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
  fun reloadReplacesTheProcessUnderItsKeyAndKeepsItsPane() = registryTest { registry ->
    val old = registry.open(CWD, "/s/a.jsonl")
    registry.show(Pane.Secondary, old)

    val replacement = registry.reload(old)

    assertTrue(old.closed)
    assertNotSame(old, replacement)
    assertEquals(CWD, replacement.cwd)
    assertEquals("/s/a.jsonl", replacement.sessionFile.value)
    assertSame(replacement, registry.processes.value[ChatKey("/s/a.jsonl")])
    assertEquals(mapOf(Pane.Secondary to replacement), registry.shown.value)
  }

  @Test
  fun reloadIdleReloadsShownAndParksHiddenIdleProcesses() = registryTest { registry ->
    val shown = registry.open(CWD, "/s/shown.jsonl")
    val split = registry.open(CWD, "/s/split.jsonl")
    val hidden = registry.open(CWD, "/s/hidden.jsonl")
    val working = registry.open(CWD, "/s/working.jsonl")
    working.busy.value = true
    registry.show(Pane.Primary, shown)
    registry.show(Pane.Secondary, split)

    registry.reloadIdle()

    assertTrue(shown.closed && split.closed)
    assertTrue(hidden.parked)
    assertFalse(hidden.closed)
    assertFalse(working.closed)
    val panes = registry.shown.value
    assertEquals("/s/shown.jsonl", panes[Pane.Primary]?.sessionFile?.value)
    assertEquals("/s/split.jsonl", panes[Pane.Secondary]?.sessionFile?.value)
    assertTrue(panes.values.none { it === shown || it === split })
    assertEquals(
      setOf(ChatKey("/s/shown.jsonl"), ChatKey("/s/split.jsonl"), ChatKey("/s/working.jsonl")),
      registry.processes.value.keys,
    )
  }

  @Test
  fun aChatTakingOverAParkedSessionClosesTheStaleCopy() = registryTest { registry ->
    val stale = registry.open(CWD, "/s/a.jsonl")
    registry.reloadIdle()
    val takeover = registry.open(CWD, null)
    takeover.sessionFile.value = "/s/a.jsonl"
    settle()

    assertTrue(stale.closed)
    // Forking on to another session leaves nothing under the first one to reopen.
    takeover.sessionFile.value = "/s/b.jsonl"
    settle()
    val reopened = registry.open(CWD, "/s/a.jsonl")
    assertNotSame(stale, reopened)
    assertNotSame(takeover, reopened)
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
  fun runEndedReportsEachEndOfWorkButNotIdleStartsOrClosing() = registryTest { registry ->
    val a = registry.open(CWD, "/s/a.jsonl")
    val b = registry.open(CWD, "/s/b.jsonl")
    settle()
    assertEquals(emptyList<FakeProcess>(), ended)

    a.busy.value = true
    b.busy.value = true
    settle()
    a.busy.value = false
    settle()
    a.busy.value = true
    settle()
    a.busy.value = false
    settle()
    registry.close(b)
    b.busy.value = false
    settle()

    assertEquals(listOf(a, a), ended)
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
    registry.show(Pane.Secondary, stuckShown)

    registry.abortAll()
    settle()

    assertEquals(1, worker.aborts)
    assertFalse(worker.closed)
    assertEquals(0, idle.aborts)
    assertTrue(stuckHidden.closed)
    assertNull(registry.keyOf(stuckHidden))
    assertTrue(stuckShown.closed)
    val restarted = registry.shown.value[Pane.Secondary]
    assertNotSame(stuckShown, restarted)
    assertEquals("/s/stuck-shown.jsonl", restarted?.sessionFile?.value)
  }

  private fun registryTest(
    idleCloseMillis: Long = AgentRegistry.IDLE_CLOSE_MILLIS,
    block: suspend (AgentRegistry<FakeProcess>) -> Unit,
  ) = runBlocking {
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    try {
      block(
        AgentRegistry(scope, idleCloseMillis, ended::add) { cwd, path ->
          FakeProcess(cwd, path).also(created::add)
        },
      )
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
