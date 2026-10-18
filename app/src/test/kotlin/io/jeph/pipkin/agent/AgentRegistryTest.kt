package io.jeph.pipkin.agent

import io.jeph.pipkin.workspace.Pane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    val backgroundTasks = MutableStateFlow(0)

    // Unconfined, so it follows busy and backgroundTasks as soon as a test sets them, like AgentProcess's.
    override val holdsPi: StateFlow<Boolean> = combine(busy, backgroundTasks) { working, tasks -> working || tasks > 0 }
      .stateIn(CoroutineScope(Dispatchers.Unconfined), SharingStarted.Eagerly, false)
    var closed = false
    var parked = false
    var restarts = 0
    var aborts = 0
    var abortFails = false
    var backgroundStops = 0

    override suspend fun stopBackgroundTasks() {
      backgroundStops += 1
      backgroundTasks.value = 0
    }

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
  private val maxRunning = MutableStateFlow(MAX_RUNNING)
  private val idleParkMillis = MutableStateFlow(IdleStop.Default.millis)

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
  fun hiddenIdleProcessesParkAfterTheIdleDelay() = registryTest { registry ->
    idleParkMillis.value = 50
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
  fun backgroundTasksKeepAHiddenChatUntilTheyEnd() = registryTest { registry ->
    idleParkMillis.value = 50
    val waiting = registry.open(CWD, "/s/waiting.jsonl")
    waiting.backgroundTasks.value = 1
    delay(300)

    assertFalse(waiting.parked)

    waiting.backgroundTasks.value = 0
    delay(300)

    assertTrue(waiting.parked)
  }

  @Test
  fun aChatWithBackgroundTasksHoldsItsSlotButIsNotBusy() = registryTest { registry ->
    val waiting = registry.open(CWD, "/s/waiting.jsonl")
    List(3) { registry.open(CWD, "/s/$it.jsonl") }.forEach { it.busy.value = true }
    waiting.backgroundTasks.value = 2
    settle()

    assertThrows(TooManyRunning::class.java) { registry.open(CWD, null) }
    registry.reloadIdle()
    assertFalse(waiting.parked || waiting.closed)

    created.forEach { if (it !== waiting) it.busy.value = false }
    settle()
    assertFalse(registry.busy.value)
    assertTrue(registry.holding.value)
    // Background tasks ending isn't a run ending.
    waiting.backgroundTasks.value = 0
    settle()
    assertFalse(registry.holding.value)
    assertFalse(ended.contains(waiting))
  }

  @Test
  fun neverKeepsHiddenIdleProcessesUntilTheDelayChanges() = registryTest { registry ->
    idleParkMillis.value = null
    val hidden = registry.open(CWD, "/s/hidden.jsonl")
    delay(300)

    assertFalse(hidden.parked)

    idleParkMillis.value = 50
    delay(300)

    assertTrue(hidden.parked)
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
  fun raisingTheLimitMakesRoomForAnotherProcess() = registryTest { registry ->
    List(4) { registry.open(CWD, "/s/$it.jsonl") }.forEach { it.busy.value = true }
    assertThrows(TooManyRunning::class.java) { registry.open(CWD, null) }

    maxRunning.value = 5

    registry.open(CWD, null)
    assertEquals(5, registry.processes.value.size)
  }

  @Test
  fun loweringTheLimitParksHiddenIdleProcessesDownToItLeastRecentlyShownFirst() = registryTest { registry ->
    val first = registry.open(CWD, "/s/first.jsonl")
    val second = registry.open(CWD, "/s/second.jsonl")
    val shown = registry.open(CWD, "/s/shown.jsonl")
    val working = registry.open(CWD, "/s/working.jsonl")
    working.busy.value = true
    registry.show(Pane.Primary, shown)

    maxRunning.value = 3
    settle()

    assertTrue(first.parked)
    assertFalse(second.parked || shown.parked || working.parked)

    maxRunning.value = 2
    settle()

    assertTrue(second.parked)
    assertFalse(shown.parked || working.parked)
    assertEquals(2, registry.processes.value.size)
  }

  @Test
  fun aLoweredLimitAppliesAsRunsEnd() = registryTest { registry ->
    val (first, second, third) = List(3) { registry.open(CWD, "/s/$it.jsonl") }
    listOf(first, second, third).forEach { it.busy.value = true }
    settle()

    maxRunning.value = 2
    settle()
    assertEquals(3, registry.processes.value.size)

    first.busy.value = false
    settle()
    assertTrue(first.parked)
    assertEquals(listOf(first), ended)

    second.busy.value = false
    settle()
    assertFalse(second.parked || third.parked)
    assertEquals(2, registry.processes.value.size)
  }

  @Test
  fun openingParksDownToALoweredLimit() = registryTest { registry ->
    idleParkMillis.value = null
    val main = registry.open(CWD, "/s/main.jsonl")
    val split = registry.open(CWD, "/s/split.jsonl")
    val working = registry.open(CWD, "/s/working.jsonl")
    working.busy.value = true
    registry.show(Pane.Primary, main)
    registry.show(Pane.Secondary, split)
    maxRunning.value = 2
    settle()
    registry.show(Pane.Primary, null)
    registry.show(Pane.Secondary, null)

    val next = registry.open(CWD, null)

    assertTrue(main.parked && split.parked)
    assertEquals(setOf(working, next), registry.processes.value.values.toSet())
  }

  @Test
  fun aRefusedOpenParksNothingSoThePaneCanShowItsChatAgain() = registryTest { registry ->
    val shown = registry.open(CWD, "/s/shown.jsonl")
    val workers = List(3) { registry.open(CWD, "/s/w$it.jsonl") }
    workers.forEach { it.busy.value = true }
    registry.show(Pane.Primary, shown)
    maxRunning.value = 2
    settle()

    // As ShellActions.show does: hide the pane's chat while the next one opens, and show it again when refused.
    registry.show(Pane.Primary, null)
    val refused = assertThrows(TooManyRunning::class.java) { registry.open(CWD, null) }
    registry.show(Pane.Primary, shown)

    assertFalse(shown.parked)
    assertSame(shown, registry.shown.value[Pane.Primary])
    assertEquals(
      "4 chats are working or on screen. That's more than the limit of 2, so stop some to open another.",
      refused.message,
    )
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

  @Test
  fun abortAllStopsBackgroundTasksWithoutAbortingIdleChats() = registryTest { registry ->
    val waiting = registry.open(CWD, "/s/waiting.jsonl")
    val working = registry.open(CWD, "/s/working.jsonl")
    val idle = registry.open(CWD, "/s/idle.jsonl")
    waiting.backgroundTasks.value = 1
    working.busy.value = true
    working.backgroundTasks.value = 1
    settle()

    registry.abortAll()
    settle()

    assertEquals(0, waiting.aborts)
    assertEquals(1, waiting.backgroundStops)
    assertEquals(1, working.aborts)
    assertEquals(1, working.backgroundStops)
    assertEquals(0, idle.backgroundStops)
    assertFalse(registry.holding.value)
  }

  @Test
  fun stopAllEndsEveryPiAndRestartShownBringsBackOnlyTheChatsOnScreen() = registryTest { registry ->
    val shownWorker = registry.open(CWD, "/s/shown.jsonl")
    val stuckHidden = registry.open(CWD, "/s/stuck.jsonl")
    val hidden = registry.open(CWD, "/s/hidden.jsonl")
    listOf(shownWorker, stuckHidden).forEach { it.busy.value = true }
    stuckHidden.abortFails = true
    registry.show(Pane.Primary, shownWorker)

    registry.stopAll()

    assertEquals(1, shownWorker.aborts)
    assertEquals(1, stuckHidden.aborts)
    assertTrue(created.all { it.parked && !it.closed })
    // The shown chat stays in its pane with its history; hidden ones leave the registry for reopening later.
    assertSame(shownWorker, registry.shown.value[Pane.Primary])
    assertEquals(setOf(ChatKey("/s/shown.jsonl")), registry.processes.value.keys)

    registry.restartShown()

    assertEquals(1, shownWorker.restarts)
    assertFalse(shownWorker.parked)
    assertTrue(stuckHidden.parked && hidden.parked)
    assertSame(stuckHidden, registry.open(CWD, "/s/stuck.jsonl"))
    assertEquals(1, stuckHidden.restarts)
  }

  private fun registryTest(block: suspend (AgentRegistry<FakeProcess>) -> Unit) = runBlocking {
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    try {
      block(
        AgentRegistry(scope, maxRunning, idleParkMillis, ended::add) { cwd, path ->
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

    /** The limit the tests start with, which their names count from. */
    const val MAX_RUNNING = 4
  }
}
