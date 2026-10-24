package io.jeph.pipkin.ui.subagents

import io.jeph.pipkin.agent.SubagentAsker
import io.jeph.pipkin.agent.SubagentRun
import io.jeph.pipkin.agent.SubagentStatus
import io.jeph.pipkin.agent.TimelineItem.ToolCall
import io.jeph.pipkin.agent.ToolStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentCallsTest {
  @Test
  fun aListedRunGivesThePhaseAndOnlyTheNamedOneAsks() {
    val running = run("r1", "c1", SubagentStatus.Running)
    val other = run("r2", "c2", SubagentStatus.Running)
    val asker = SubagentAsker.One(running)

    assertEquals(SubagentPhase.Asking, subagentPhase(call("c1"), running, asker, working = true))
    assertEquals(SubagentPhase.Running, subagentPhase(call("c2"), other, asker, working = true))
    // Several asking at once names none of them.
    assertEquals(SubagentPhase.Running, subagentPhase(call("c1"), running, SubagentAsker.Several, working = true))
    val queued = run("r3", "c3", SubagentStatus.Queued)
    assertEquals(SubagentPhase.Queued, subagentPhase(call("c3"), queued, asker, working = true))
    // The run outranks a result that may lag behind it.
    val stopped = run("r4", "c4", SubagentStatus.Stopped)
    assertEquals(SubagentPhase.Stopped, subagentPhase(call("c4", output = ""), stopped, asker, working = false))
  }

  @Test
  fun aCallWithoutItsRunReadsItsResult() {
    val unknown = SubagentAsker.Unknown
    assertEquals(SubagentPhase.Done, subagentPhase(call("c1", output = "found"), null, unknown, working = false))
    assertEquals(
      SubagentPhase.Failed,
      subagentPhase(call("c1", output = "boom", isError = true), null, unknown, working = false),
    )
    val stopped = call("c1", output = "stopped", details = JSONObject().put("status", "stopped"))
    assertEquals(SubagentPhase.Stopped, subagentPhase(stopped, null, unknown, working = false))
    val failed = call("c1", output = "boom", details = JSONObject().put("status", "failed"))
    assertEquals(SubagentPhase.Failed, subagentPhase(failed, null, unknown, working = false))
  }

  @Test
  fun aCallWithNoResultIsQueuedWhileTheChatWorksAndInterruptedOnceItStopped() {
    // A settled run marks its calls done, with no output: that's not a result.
    val settled = call("c1").copy(status = ToolStatus.Done)
    assertEquals(SubagentPhase.Queued, subagentPhase(settled, null, SubagentAsker.Unknown, working = true))
    assertEquals(SubagentPhase.Interrupted, subagentPhase(settled, null, SubagentAsker.Unknown, working = false))
    assertEquals(SubagentPhase.Interrupted, ChatSubagents.None.phase(settled))
  }

  @Test
  fun groupsPutTheAskingRunFirstAndQueuedOnesWithTheRunning() {
    val asking = run("r1", "c1", SubagentStatus.Running)
    val running = run("r2", "c2", SubagentStatus.Running)
    val queued = run("r3", "c3", SubagentStatus.Queued)
    val done = run("r4", "c4", SubagentStatus.Done)
    val failed = run("r5", "c5", SubagentStatus.Failed)
    val subagents = ChatSubagents(listOf(done, asking, queued, running, failed), SubagentAsker.One(asking), true)

    val groups = subagents.groups()

    assertEquals(listOf(asking), groups.waiting)
    assertEquals(listOf(queued, running), groups.running)
    assertEquals(listOf(done, failed), groups.finished)
    assertEquals(3, subagents.active)
    // "Running" counts the one asking but not the queued one.
    assertEquals(2, subagents.running)
    assertEquals(1, subagents.queued)
    assertTrue(subagents.waiting)
    assertFalse(ChatSubagents(listOf(running), SubagentAsker.Unknown, true).waiting)
  }

  @Test
  fun factsFindTheTranscriptFromTheRunThenTheResultThenTheChatsFiles() {
    val listed = run("r1", "c1", SubagentStatus.Running).copy(sessionFile = "/s/sub/a.jsonl")
    val live = ChatSubagents(listOf(listed), SubagentAsker.Unknown, working = true)
    assertEquals(TranscriptSource.File("/s/sub/a.jsonl"), subagentFacts("c1", call("c1"), live, "/s/p.jsonl").source)

    val details = JSONObject().put("sessionFile", "/s/sub/b.jsonl").put("agent", "Explore").put("description", "auth")
    val finished = call("c2", output = "done", details = details)
    val facts = subagentFacts("c2", finished, ChatSubagents.None, "/s/p.jsonl")
    assertEquals(TranscriptSource.File("/s/sub/b.jsonl"), facts.source)
    assertEquals("Explore", facts.agent)
    assertEquals("auth", facts.description)

    val interrupted = subagentFacts("c3", call("c3"), ChatSubagents.None, "/s/p.jsonl")
    assertEquals(TranscriptSource.Lookup("/s/p.jsonl", "c3"), interrupted.source)
    assertEquals(SubagentPhase.Interrupted, interrupted.phase)
    // The call's own arguments name it while nothing else does.
    assertEquals("General", interrupted.agent)
    assertEquals("fix it", interrupted.description)

    // A start failure's result names no run: its subagent never began, so there's no file.
    val startFailed = JSONObject().put("extension", "pipkin-subagents").put("status", "failed")
    val refused = subagentFacts("c6", call("c6", "Unknown agent", true, startFailed), ChatSubagents.None, "/s/p.jsonl")
    assertEquals(TranscriptSource.NotStarted("Unknown agent"), refused.source)
    assertEquals(SubagentPhase.Failed, refused.phase)
    // Only an error says the subagent didn't start.
    val noDetails = subagentFacts("c7", call("c7", "ran"), ChatSubagents.None, "/s/p.jsonl")
    assertEquals(TranscriptSource.Lookup("/s/p.jsonl", "c7"), noDetails.source)

    // A run in memory, or a chat with no file, has no transcript to find.
    val inMemory = ChatSubagents(listOf(run("r4", "c4", SubagentStatus.Running)), SubagentAsker.Unknown, true)
    assertEquals(TranscriptSource.None, subagentFacts("c4", call("c4"), inMemory, "/s/p.jsonl").source)
    assertEquals(TranscriptSource.None, subagentFacts("c5", null, ChatSubagents.None, null).source)
  }

  @Test
  fun aRunThatFailedBeforeSavingAnythingShowsItsError() {
    val failed = run("r1", "c1", SubagentStatus.Failed).copy(error = "No API key for x", messages = 0)
    val listed = ChatSubagents(listOf(failed), SubagentAsker.Unknown, working = false)
    val output = "The subagent failed: No API key for x"
    val call = call("c1", output, isError = true, details = JSONObject().put("id", "r1").put("status", "failed"))
    fun source(subagents: ChatSubagents) = subagentFacts("c1", call, subagents, "/s/p.jsonl").source
    assertEquals(TranscriptSource.NotStarted("No API key for x"), source(listed))
    // Once the run is dropped, its result says the same.
    assertEquals(TranscriptSource.NotStarted(output), source(ChatSubagents.None))
    // One that failed later ran in memory: nothing to show but that.
    val later = ChatSubagents(listOf(failed.copy(messages = 3)), SubagentAsker.Unknown, working = false)
    assertEquals(TranscriptSource.None, source(later))
  }

  @Test
  fun statsCountRepliesTokensAndTimeFromFirstMessageToLastReplysEnd() {
    val messages = JSONArray(
      """[{"role":"user","content":"go","timestamp":1000},
        {"role":"assistant","timestamp":2000,"durationMs":500,"usage":{"totalTokens":100}},
        {"role":"toolResult","toolCallId":"t","timestamp":3000},
        {"role":"assistant","timestamp":4000,"durationMs":1000,"usage":{"totalTokens":250}}]""",
    )

    val stats = transcriptStats(messages)
    assertEquals(TranscriptStats(turns = 2, tokens = 350, firstAt = 1000, lastAt = 5000), stats)
    assertEquals(4000L, stats.durationMillis())
    assertNull(transcriptStats(JSONArray()).durationMillis())
  }

  @Test
  fun anInterruptedRunTakesUntilItWasCutOffWhenThatsLaterThanItsLastReply() {
    // Cut off in a tool call 8 s after it began; its transcript ends with the reply that made the call.
    val stats = TranscriptStats(turns = 1, tokens = 10, firstAt = 1000, lastAt = 1200)
    assertEquals(200L, stats.durationMillis())
    assertEquals(8000L, stats.durationMillis(cutOffAt = 9000))
    assertEquals(200L, stats.durationMillis(cutOffAt = 500))

    val cutOff = ChatSubagents(emptyList(), SubagentAsker.Unknown, working = false, cutOff = mapOf("c1" to 9000L))
    assertEquals(9000L, subagentFacts("c1", call("c1"), cutOff, "/s/p.jsonl").cutOffAt)
    // Only an interrupted one: a result says how it ended.
    assertNull(subagentFacts("c1", call("c1", output = "done"), cutOff, "/s/p.jsonl").cutOffAt)
  }

  @Test
  fun pipkinsSubagentCallsDrawAsSubagentsAndOthersAsToolRows() {
    val pipkin = ChatSubagents(emptyList(), SubagentAsker.Unknown, working = true)
    val ranDetails = JSONObject().put("id", "r1").put("status", "done")
    assertTrue(pipkin.startsSubagent(call("c1")))
    // A `!subagent` the user ran, or any other tool, is never one.
    assertFalse(pipkin.startsSubagent(call("c1").copy(byUser = true)))
    assertFalse(pipkin.startsSubagent(call("c1").copy(toolName = "read")))

    // Standing aside, another extension's `subagent` tool is an ordinary tool, running or done.
    val other = ChatSubagents(emptyList(), SubagentAsker.Unknown, working = true, tool = SubagentTool.Other)
    assertFalse(other.startsSubagent(call("c1")))
    assertFalse(other.startsSubagent(call("c1", output = "theirs", details = ranDetails)))
  }

  @Test
  fun aFinishedCallIsASubagentOnlyWhenItsResultSaysSoInALiveChatAsInAnArchivedOne() {
    val live = ChatSubagents(emptyList(), SubagentAsker.Unknown, working = true)
    for (subagents in listOf(live, ChatSubagents.None)) {
      val marked = JSONObject().put("extension", "pipkin-subagents").put("status", "failed")
      assertTrue(subagents.startsSubagent(call("c1", output = "Unknown agent", isError = true, details = marked)))
      // An older build's results name the run or its transcript.
      assertTrue(subagents.startsSubagent(call("c1", output = "done", details = JSONObject().put("id", "r1"))))
      val inMemory = JSONObject().put("sessionFile", "/s/sub.jsonl")
      assertTrue(subagents.startsSubagent(call("c1", output = "done", details = inMemory)))
      // Unfinished: it can't be told from another extension's call cut off, so it stays a subagent.
      assertTrue(subagents.startsSubagent(call("c1")))
      // Another extension's result, as one made before the user replaced Pipkin's tool and reloaded: no details, or
      // details of its own; a call refused before any extension ran has none either.
      assertFalse(subagents.startsSubagent(call("c1", output = "theirs")))
      assertFalse(subagents.startsSubagent(call("c1", output = "blocked", isError = true)))
      assertFalse(subagents.startsSubagent(call("c1", output = "theirs", details = JSONObject().put("agent", "x"))))
    }
  }

  private fun run(id: String, toolCallId: String, status: SubagentStatus) = SubagentRun(
    id = id,
    toolCallId = toolCallId,
    agent = "Explore",
    description = "auth",
    status = status,
    startedAt = 1L,
    endedAt = null,
    turn = 1,
    tokens = 10,
    lastActivity = null,
    error = null,
    sessionFile = null,
    messages = 1,
    inFlight = 0,
  )

  private fun call(id: String, output: String? = null, isError: Boolean = false, details: JSONObject? = null) =
    ToolCall(
      key = "m0.$id",
      id = id,
      toolName = "subagent",
      args = JSONObject().put("agent", "General").put("description", "fix it"),
      output = output,
      isError = isError,
      status = if (output == null) ToolStatus.Running else ToolStatus.Done,
      details = details,
    )
}
