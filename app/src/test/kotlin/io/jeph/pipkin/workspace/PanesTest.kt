package io.jeph.pipkin.workspace

import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.ManagedProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PanesTest {
  private class FakeProcess(override val cwd: String, sessionPath: String?) : ManagedProcess {
    override val sessionFile = MutableStateFlow(sessionPath)
    override val busy = MutableStateFlow(false)

    override suspend fun abort() = Unit

    override fun park() = Unit

    override fun restart() = Unit

    override fun close() = Unit
  }

  private val archived = mutableSetOf<String>()

  @Test
  fun backShowsWhatThePaneShowedBeforeNewestFirst() = withPanes { registry, panes ->
    val a = registry.open(WORK, "/s/a.jsonl")
    val b = registry.open(WORK, "/s/b.jsonl")
    val c = registry.open(WORK, "/s/c.jsonl")
    panes.show(registry, Pane.Primary, a)
    panes.show(registry, Pane.Primary, b)
    panes.show(registry, Pane.Primary, c)

    assertTrue(panes.back(Pane.Primary))
    assertSame(b, registry.shown.value[Pane.Primary])
    assertTrue(panes.back(Pane.Primary))
    assertSame(a, registry.shown.value[Pane.Primary])
    assertFalse(panes.back(Pane.Primary))
    assertSame(a, registry.shown.value[Pane.Primary])
  }

  @Test
  fun showingAChatAgainMovesItToTheTopInsteadOfRepeatingIt() = withPanes { registry, panes ->
    val a = registry.open(WORK, "/s/a.jsonl")
    val b = registry.open(WORK, "/s/b.jsonl")
    panes.show(registry, Pane.Primary, a)
    panes.show(registry, Pane.Primary, b)
    panes.show(registry, Pane.Primary, a)
    panes.show(registry, Pane.Primary, b)

    assertEquals(listOf(PaneItem.Chat(a)), panes.history.value[Pane.Primary])
  }

  @Test
  fun aChatTheOtherPaneShowsLeavesThisPanesHistory() = withPanes { registry, panes ->
    val a = registry.open(WORK, "/s/a.jsonl")
    val b = registry.open(WORK, "/s/b.jsonl")
    panes.show(registry, Pane.Primary, a)
    panes.show(registry, Pane.Primary, b)

    panes.show(registry, Pane.Secondary, a)

    assertFalse(panes.back(Pane.Primary))
    assertSame(b, registry.shown.value[Pane.Primary])
    assertSame(a, registry.shown.value[Pane.Secondary])
  }

  @Test
  fun backPassesOverArchivedChatsAndReopensClosedOnes() = withPanes { registry, panes ->
    val a = registry.open(WORK, "/s/a.jsonl")
    val b = registry.open(WORK, "/s/b.jsonl")
    val unsaved = registry.open(WORK, null)
    val c = registry.open(WORK, "/s/c.jsonl")
    panes.show(registry, Pane.Primary, a)
    panes.show(registry, Pane.Primary, b)
    panes.show(registry, Pane.Primary, unsaved)
    panes.show(registry, Pane.Primary, c)
    archived += "/s/b.jsonl"
    registry.close(a)
    registry.close(unsaved)

    assertTrue(panes.back(Pane.Primary))

    val reopened = registry.shown.value.getValue(Pane.Primary)
    assertNotSame(a, reopened)
    assertEquals("/s/a.jsonl", reopened.sessionFile.value)
    assertEquals(null, panes.history.value[Pane.Primary])
  }

  @Test
  fun forgottenChatsLeaveEveryHistory() = withPanes { registry, panes ->
    val a = registry.open(WORK, "/s/a.jsonl")
    val b = registry.open(WORK, "/s/b.jsonl")
    panes.show(registry, Pane.Primary, a)
    panes.show(registry, Pane.Primary, b)

    panes.forgetChats(listOf("/s/a.jsonl"))

    assertFalse(panes.back(Pane.Primary))
  }

  @Test
  fun showLastInShowsTheNewestItemInThatFolderAndKeepsWhatThePaneShowed() = withPanes { registry, panes ->
    val first = registry.open(WORK, "/s/first.jsonl")
    val other = registry.open(OTHER, "/s/other.jsonl")
    val newer = registry.open(WORK, "/s/newer.jsonl")
    val elsewhere = registry.open(OTHER, "/s/elsewhere.jsonl")
    panes.show(registry, Pane.Primary, first)
    panes.show(registry, Pane.Primary, newer)
    panes.show(registry, Pane.Primary, other)
    panes.show(registry, Pane.Primary, elsewhere)

    assertTrue(panes.showLastIn(Pane.Primary, "$WORK/"))

    assertSame(newer, registry.shown.value[Pane.Primary])
    assertEquals(
      listOf(PaneItem.Chat(first), PaneItem.Chat(other), PaneItem.Chat(elsewhere)),
      panes.history.value[Pane.Primary],
    )
    assertFalse(panes.showLastIn(Pane.Primary, "/nowhere"))
  }

  @Test
  fun clearingAPaneForgetsItsHistory() = withPanes { registry, panes ->
    val a = registry.open(WORK, "/s/a.jsonl")
    val b = registry.open(WORK, "/s/b.jsonl")
    panes.show(registry, Pane.Secondary, a)
    panes.show(registry, Pane.Secondary, b)

    panes.clear(Pane.Secondary)
    registry.show(Pane.Secondary, null)

    assertFalse(panes.back(Pane.Secondary))
  }

  /** Shows [chat] in [pane] as the shell does: the registry first, then [Panes] records it. */
  private fun Panes<FakeProcess>.show(registry: AgentRegistry<FakeProcess>, pane: Pane, chat: FakeProcess) {
    val previous = itemIn(pane)
    assertTrue(registry.show(pane, chat))
    chatShown(pane, chat, previous)
  }

  private fun withPanes(block: suspend (AgentRegistry<FakeProcess>, Panes<FakeProcess>) -> Unit) = runBlocking {
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    try {
      val registry = AgentRegistry(scope) { cwd, path -> FakeProcess(cwd, path) }
      block(registry, Panes(registry) { it in archived })
    } finally {
      scope.cancel()
    }
  }

  private companion object {
    const val WORK = "/home/work"
    const val OTHER = "/home/other"
  }
}
