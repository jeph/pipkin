package io.jeph.pipkin.agent

import io.jeph.pipkin.agent.TimelineItem.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepFigureTest {
  @Test
  fun anEditCountsTheLinesOfPisDiff() {
    // Pi's diff numbers each line after its sign; context lines and the elision marker start with a space.
    val diff = "   1 keep\n-  2 old one\n-  3 old two\n+  2 new\n     ...\n+ 10 added\n  11 keep"
    assertEquals(StepFigure.Lines(2, 2), figure("edit", details = JSONObject().put("diff", diff)))
  }

  @Test
  fun anEditWithoutADiffOrThatFailedHasNoFigure() {
    assertNull(figure("edit"))
    assertNull(figure("edit", details = JSONObject()))
    assertNull(figure("edit", failed = true, details = JSONObject().put("diff", "+1 a")))
  }

  @Test
  fun aWriteCountsTheLinesItWrote() {
    fun write(content: String) = figure("write", args = JSONObject().put("content", content))
    assertEquals(StepFigure.Lines(3, 0), write("a\nb\nc"))
    assertEquals(StepFigure.Lines(3, 0), write("a\nb\nc\n"))
    assertEquals(StepFigure.Lines(1, 0), write("\n"))
    assertEquals(StepFigure.Lines(0, 0), write(""))
    assertNull(figure("write", args = JSONObject().put("content", "a"), failed = true))
  }

  @Test
  fun aShellCommandShowsHowItEnded() {
    assertNull(figure("bash", output = "done\n"))
    assertEquals(StepFigure.Exit(1), figure("bash", output = "oops\n\nCommand exited with code 1", failed = true))
    assertEquals(StepFigure.Exit(127), figure("bash", output = "Command exited with code 127\n", failed = true))
    assertEquals(StepFigure.TimedOut, figure("bash", output = "x\n\nCommand timed out after 30 seconds", failed = true))
    assertEquals(StepFigure.Stopped, figure("bash", output = "Command aborted", failed = true))
    // Only Pi's own last line counts, not the same words earlier in the output.
    assertNull(figure("bash", output = "Command exited with code 2\nok"))
  }

  @Test
  fun otherToolsHaveNoFigure() {
    assertNull(figure("read", output = "Command exited with code 1"))
  }

  @Test
  fun theTimelineKeepsFiguresThroughLiveResultsAndHistory() {
    val edit = """{"type":"toolCall","id":"e","name":"edit","arguments":{"path":"a","edits":[]}}"""
    val details = """{"diff":"+1 new\n-1 old\n-2 old"}"""

    val live = ChatTimeline { 0 }
    fun apply(json: String) = live.apply(JSONObject(json))
    apply("""{"type":"message_end","message":{"role":"assistant","stopReason":"toolUse","content":[$edit]}}""")
    assertNull(live.tool().figure)
    apply(
      """{"type":"tool_execution_end","toolCallId":"e","toolName":"edit","isError":false,
        "result":{"content":[],"details":$details}}""",
    )
    assertEquals(StepFigure.Lines(1, 2), live.tool().figure)

    val saved = ChatTimeline { 0 }
    saved.seed(
      JSONArray(
        """[{"role":"assistant","stopReason":"toolUse","content":[$edit]},
          {"role":"toolResult","toolCallId":"e","isError":false,"content":[],"details":$details},
          {"role":"bashExecution","command":"false","output":"","exitCode":1},
          {"role":"bashExecution","command":"sleep 9","output":"","exitCode":null,"cancelled":true}]""",
      ),
    )
    val items = saved.state.value.items.filterIsInstance<ToolCall>()
    assertEquals(listOf(StepFigure.Lines(1, 2), StepFigure.Exit(1), StepFigure.Stopped), items.map { it.figure })
  }

  private fun ChatTimeline.tool() = state.value.items.filterIsInstance<ToolCall>().single()

  private fun figure(
    tool: String,
    args: JSONObject? = null,
    output: String? = null,
    failed: Boolean = false,
    details: JSONObject? = null,
  ) = stepFigure(tool, args, output, failed, details)
}
