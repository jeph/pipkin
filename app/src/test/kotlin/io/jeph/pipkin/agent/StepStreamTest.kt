package io.jeph.pipkin.agent

import io.jeph.pipkin.agent.TimelineItem.AssistantText
import io.jeph.pipkin.agent.TimelineItem.Steps
import io.jeph.pipkin.agent.TimelineItem.Thinking
import io.jeph.pipkin.agent.TimelineItem.ToolCall
import io.jeph.pipkin.agent.TimelineItem.TurnFooter
import io.jeph.pipkin.agent.TimelineItem.User
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepStreamTest {
  @Test
  fun aLoneStepStaysARowAndTwoFoldUnderTheFirstStepsKey() {
    val one = StepStream.of(listOf(user("m0"), tool("m1.0")))
    assertEquals(listOf("m0", "m1.0"), one.entries.map { it.key })

    val two = StepStream.of(listOf(user("m0"), tool("m1.0"), tool("m2.0")))
    assertEquals(listOf("m0", "steps:m1.0"), two.entries.map { it.key })

    // More steps keep the fold's key, so the list keeps its row.
    val three = StepStream.of(listOf(user("m0"), tool("m1.0"), tool("m2.0"), thinking("m3.0")))
    assertEquals(listOf("m0", "steps:m1.0"), three.entries.map { it.key })
    assertEquals(3, (three.entries[1] as Steps).steps.size)
  }

  @Test
  fun anythingButAStepEndsAFold() {
    val separators = listOf(
      text("m2.0"),
      user("m2"),
      TimelineItem.Error("m2.error", ErrorCause.Request(null)),
      TimelineItem.Notice("n0", NoticeKind.Retrying),
      TimelineItem.ExtensionNotice("en0", NoticeLevel.Info, "hi"),
      TimelineItem.ExtensionMessage("m2", "note", "hi", emptyList()),
      tool("m2", byUser = true),
    )
    for (separator in separators) {
      val stream = StepStream.of(listOf(tool("a"), thinking("b"), separator, tool("c"), tool("d")))
      assertEquals(
        "after ${separator::class.simpleName}",
        listOf("steps:a", separator.key, "steps:c"),
        stream.entries.map { it.key },
      )
    }
  }

  @Test
  fun theUsersOwnCommandsStayOutOfTheAgentsFolds() {
    val stream = StepStream.of(listOf(tool("m0", byUser = true), tool("m1", byUser = true)))
    assertEquals(listOf("m0", "m1"), stream.entries.map { it.key })
  }

  @Test
  fun aFoldSaysHowItsRunWent() {
    val steps = listOf(tool("a"), tool("b"), tool("c"))

    assertEquals(StepsOutcome.Working, fold(steps + TurnFooter("t0", 0, null)).outcome)
    assertEquals(StepsOutcome.Done, fold(steps + TurnFooter("t0", 0, 5)).outcome)
    assertEquals(StepsOutcome.Stopped, fold(steps + TurnFooter("t0", 0, 5, stopped = true)).outcome)
    // A reply after the steps means the run went on past them.
    assertEquals(StepsOutcome.Done, fold(steps + text("r") + TurnFooter("t0", 0, 5, stopped = true)).outcome)
  }

  @Test
  fun aWorkingFoldShowsTheLatestStepStillRunning() {
    val parallel = listOf(
      tool("a", status = ToolStatus.Running),
      tool("b", status = ToolStatus.Running),
      tool("c"),
      TurnFooter("t0", 0, null),
    )
    assertEquals("b", fold(parallel).current.key)
    assertEquals("c", fold(listOf(tool("a"), tool("c"), TurnFooter("t0", 0, null))).current.key)
    assertEquals("b", fold(listOf(tool("a"), Thinking("b", "", streaming = true))).current.key)
  }

  @Test
  fun expandingListsAFoldsStepsUnderIt() {
    val stream = StepStream.of(listOf(user("m0"), tool("a"), tool("b"), text("r"), tool("c"), tool("d"), text("s")))
    assertEquals(stream.entries, stream.expand(emptySet()).items)

    val rows = stream.expand(setOf("steps:c", "gone"))
    assertEquals(listOf("m0", "steps:a", "r", "steps:c", "c", "d", "s"), rows.items.map { it.key })
    assertTrue(rows.isListedStep("c"))
    assertFalse(rows.isLastListedStep("c"))
    assertTrue(rows.isLastListedStep("d"))
    assertFalse(rows.isListedStep("a"))

    assertEquals("steps:c", stream.foldHolding("d"))
    assertNull(stream.foldHolding("r"))
  }

  @Test
  fun stopEndsTheLiveRunAsStopped() {
    val timeline = ChatTimeline { 1_000 }
    fun apply(json: String) = timeline.apply(JSONObject(json))
    apply("""{"type":"agent_start"}""")
    val message = """{"role":"assistant","stopReason":"toolUse","content":[$CALL_A,$CALL_B]}"""
    apply("""{"type":"message_end","message":$message}""")
    assertEquals(StepsOutcome.Working, timeline.state.value.stream.foldAt(0).outcome)

    // Stopping a running tool makes Pi end the run with an error that only says it was aborted.
    val abort = """{"role":"assistant","stopReason":"error","errorMessage":"This operation was aborted","content":[]}"""
    apply("""{"type":"message_end","message":$abort}""")
    apply("""{"type":"agent_settled","aborted":true}""")

    val stream = timeline.state.value.stream
    assertEquals(StepsOutcome.Stopped, stream.foldAt(0).outcome)
    assertTrue(timeline.state.value.items.none { it is TimelineItem.Error })
  }

  @Test
  fun aSavedStoppedRunReadsStoppedAndTheNextRunDone() {
    val timeline = ChatTimeline { 1_000 }
    timeline.seed(
      JSONArray(
        """
        [
          {"role":"user","content":"go","timestamp":1000},
          {"role":"assistant","stopReason":"toolUse","timestamp":1100,"durationMs":10,"content":[$CALL_A,$CALL_B]},
          {"role":"toolResult","toolCallId":"a","isError":true,"content":[{"type":"text","text":"Command aborted"}]},
          {"role":"assistant","stopReason":"error","errorMessage":"The operation was aborted.","timestamp":1200,
            "durationMs":10,"content":[]},
          {"role":"user","content":"again","timestamp":2000},
          {"role":"assistant","stopReason":"toolUse","timestamp":2100,"durationMs":10,"content":[$CALL_A,$CALL_B]},
          {"role":"assistant","stopReason":"aborted","errorMessage":"Request was aborted","timestamp":2200,
            "durationMs":10,"content":[]},
          {"role":"user","content":"once more","timestamp":3000},
          {"role":"assistant","stopReason":"toolUse","timestamp":3100,"durationMs":10,"content":[$CALL_A,$CALL_B]},
          {"role":"assistant","stopReason":"error","errorMessage":"overloaded","timestamp":3200,
            "durationMs":10,"content":[]},
          {"role":"user","content":"last","timestamp":4000},
          {"role":"assistant","stopReason":"toolUse","timestamp":4100,"durationMs":10,"content":[$CALL_A,$CALL_B]},
          {"role":"assistant","stopReason":"stop","timestamp":4200,"durationMs":10,"content":[]}
        ]
        """,
      ),
    )

    val folds = timeline.state.value.stream.entries.filterIsInstance<Steps>()
    assertEquals(
      listOf(StepsOutcome.Stopped, StepsOutcome.Stopped, StepsOutcome.Done, StepsOutcome.Done),
      folds.map { it.outcome },
    )
    assertEquals(StepFigure.Stopped, (folds[0].steps[0] as ToolCall).figure)
    // A failure that isn't a stop still shows, and ends its fold.
    assertEquals(1, timeline.state.value.items.count { it is TimelineItem.Error })
  }

  private fun StepStream.foldAt(index: Int) = entries.filterIsInstance<Steps>()[index]

  private fun fold(items: List<TimelineItem>) = StepStream.of(items).entries.first() as Steps

  private fun user(key: String) = User(key, "go", emptyList(), 0)

  private fun text(key: String) = AssistantText(key, "ok", streaming = false, timestamp = 0)

  private fun thinking(key: String) = Thinking(key, "hmm", streaming = false)

  private fun tool(
    key: String,
    failed: Boolean = false,
    status: ToolStatus = ToolStatus.Done,
    byUser: Boolean = false,
  ) = ToolCall(key, key, "bash", null, null, failed, status, byUser = byUser)

  private companion object {
    const val CALL_A = """{"type":"toolCall","id":"a","name":"bash","arguments":{"command":"sleep 60"}}"""
    const val CALL_B = """{"type":"toolCall","id":"b","name":"read","arguments":{"path":"x"}}"""
  }
}
