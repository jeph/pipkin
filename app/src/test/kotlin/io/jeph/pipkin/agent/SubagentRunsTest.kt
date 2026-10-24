package io.jeph.pipkin.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `runs` message of the `pipkin-subagents` extension, and telling whose question the chat's card shows. */
class SubagentRunsTest {
  @Test
  fun aRunIsReadWithEveryField() {
    val runs = SubagentRun.listFrom(
      JSONObject().put(
        "runs",
        JSONArray().put(
          JSONObject()
            .put("id", "r1")
            .put("toolCallId", "call_1")
            .put("agent", "Explore")
            .put("description", "auth redirect flow")
            .put("status", "running")
            .put("startedAt", 1_000L)
            .put("endedAt", JSONObject.NULL)
            .put("turn", 4)
            .put("tokens", 12_400L)
            .put("lastActivity", "read src/auth/logout.ts")
            .put("error", JSONObject.NULL)
            .put("sessionFile", "/s/subagents/p/c.jsonl")
            .put("messages", 12)
            .put("inFlight", 1)
            .put("steering", JSONArray().put("look at auth").put("and logout")),
        ),
      ),
    )

    assertEquals(
      listOf(
        SubagentRun(
          id = "r1",
          toolCallId = "call_1",
          agent = "Explore",
          description = "auth redirect flow",
          status = SubagentStatus.Running,
          startedAt = 1_000L,
          endedAt = null,
          turn = 4,
          tokens = 12_400L,
          lastActivity = "read src/auth/logout.ts",
          error = null,
          sessionFile = "/s/subagents/p/c.jsonl",
          messages = 12,
          inFlight = 1,
          steering = listOf("look at auth", "and logout"),
        ),
      ),
      runs,
    )
  }

  @Test
  fun runsMissingTheirIdsOrStatusAreLeftOut() {
    val good = runJson("r1", "call_1", "done")
    val runs = SubagentRun.listFrom(
      JSONObject().put(
        "runs",
        JSONArray()
          .put(runJson("", "call_2", "running"))
          .put(runJson("r3", "", "running"))
          .put(runJson("r4", "call_4", "sleeping"))
          .put(JSONObject().put("id", "r5").put("toolCallId", "call_5"))
          .put(good),
      ),
    )

    assertEquals(listOf("r1"), runs.map { it.id })
    assertEquals(SubagentStatus.Done, runs.single().status)
  }

  @Test
  fun aMessageWithoutRunsHasNone() {
    assertEquals(emptyList<SubagentRun>(), SubagentRun.listFrom(JSONObject().put("version", 1)))
  }

  @Test
  fun blankTextsAndUnsetTimesReadAsNone() {
    val run = SubagentRun.listFrom(
      JSONObject().put("runs", JSONArray().put(runJson("r1", "call_1", "queued").put("lastActivity", ""))),
    ).single()

    assertNull(run.lastActivity)
    assertNull(run.startedAt)
    assertNull(run.sessionFile)
    // A run listing no steering has none waiting.
    assertEquals(emptyList<String>(), run.steering)
  }

  @Test
  fun noPendingDialogNamesNoOne() {
    val timeline = TimelineState(items = listOf(subagentCall("call_1")))

    assertEquals(SubagentAsker.Unknown, askingSubagent(timeline, listOf(run("r1", "call_1", inFlight = 1))))
  }

  @Test
  fun noRunsNamesNoOne() {
    assertEquals(SubagentAsker.Unknown, askingSubagent(asking(), emptyList()))
  }

  @Test
  fun noRunWithCallsInFlightNamesNoOne() {
    val timeline = asking(subagentCall("call_1"))

    assertEquals(SubagentAsker.Unknown, askingSubagent(timeline, listOf(run("r1", "call_1", inFlight = 0))))
  }

  @Test
  fun theOnlyRunWithCallsInFlightIsNamed() {
    val explore = run("r1", "call_1", inFlight = 1)
    val general = run("r2", "call_2", inFlight = 0)
    val timeline = asking(subagentCall("call_1"), subagentCall("call_2"))

    assertEquals(SubagentAsker.One(explore), askingSubagent(timeline, listOf(explore, general)))
  }

  @Test
  fun severalRunsWithCallsInFlightNameNoneOfThem() {
    val timeline = asking(subagentCall("call_1"), subagentCall("call_2"))
    val runs = listOf(run("r1", "call_1", inFlight = 1), run("r2", "call_2", inFlight = 2))

    assertEquals(SubagentAsker.Several, askingSubagent(timeline, runs))
  }

  @Test
  fun aParentToolRunningMeansTheParentMayBeAsking() {
    val timeline = asking(subagentCall("call_1"), toolCall("call_bash", "bash", ToolStatus.Running))

    assertEquals(SubagentAsker.Unknown, askingSubagent(timeline, listOf(run("r1", "call_1", inFlight = 1))))
  }

  @Test
  fun theParentsFinishedToolsDontCount() {
    val explore = run("r1", "call_1", inFlight = 1)
    val timeline = asking(toolCall("call_bash", "bash", ToolStatus.Done), subagentCall("call_1"))

    assertEquals(SubagentAsker.One(explore), askingSubagent(timeline, listOf(explore)))
  }

  @Test
  fun aSubagentCallWhoseRunHasntAppearedCountsAsTheParentsOwn() {
    val explore = run("r1", "call_1", inFlight = 1)
    // The second call is still in its permission checks, so it has no run yet.
    val timeline = asking(subagentCall("call_1"), subagentCall("call_2"))

    assertEquals(SubagentAsker.Unknown, askingSubagent(timeline, listOf(explore)))
  }

  @Test
  fun stoppingTheRunThatAskedEndsItsQuestions() {
    val asker = SubagentAsker.One(run("r1", "call_1", inFlight = 1))

    assertEquals(queued, questionsStopCancels(asker, "r1", queued))
    assertEquals(queued, questionsStopCancels(asker, runId = null, queued))
  }

  @Test
  fun stoppingAnotherRunLeavesTheQuestion() {
    val asker = SubagentAsker.One(run("r1", "call_1", inFlight = 1))
    assertEquals(emptyList<String>(), questionsStopCancels(asker, "r2", queued))
  }

  @Test
  fun withSeveralAskingOnlyStopAllEndsTheQuestions() {
    assertEquals(emptyList<String>(), questionsStopCancels(SubagentAsker.Several, "r1", queued))
    assertEquals(queued, questionsStopCancels(SubagentAsker.Several, runId = null, queued))
  }

  @Test
  fun aQuestionThatMayBeTheParentsSurvivesEveryStop() {
    assertEquals(emptyList<String>(), questionsStopCancels(SubagentAsker.Unknown, "r1", queued))
    assertEquals(emptyList<String>(), questionsStopCancels(SubagentAsker.Unknown, runId = null, queued))
  }

  private val queued = listOf("ui_1", "ui_2")

  private fun asking(vararg items: TimelineItem) = TimelineState(
    items = items.toList(),
    pendingUi = ExtensionUiRequest(
      id = "d1",
      method = "confirm",
      title = "Allow bash?",
      message = "npm test",
      options = emptyList(),
      placeholder = null,
      prefill = null,
    ),
  )

  private fun subagentCall(id: String) = toolCall(id, SUBAGENT_TOOL, ToolStatus.Running)

  private fun toolCall(id: String, name: String, status: ToolStatus) = TimelineItem.ToolCall(
    key = id,
    id = id,
    toolName = name,
    args = null,
    output = null,
    isError = false,
    status = status,
  )

  private fun run(id: String, toolCallId: String, inFlight: Int) = SubagentRun(
    id = id,
    toolCallId = toolCallId,
    agent = "Explore",
    description = "auth redirect flow",
    status = SubagentStatus.Running,
    startedAt = 1_000L,
    endedAt = null,
    turn = 1,
    tokens = 0,
    lastActivity = null,
    error = null,
    sessionFile = null,
    messages = 0,
    inFlight = inFlight,
  )

  private fun runJson(id: String, toolCallId: String, status: String) =
    JSONObject().put("id", id).put("toolCallId", toolCallId).put("status", status)
}
