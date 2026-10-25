package io.jeph.pipkin.agent

import io.jeph.pipkin.agent.TimelineItem.AssistantText
import io.jeph.pipkin.agent.TimelineItem.ToolCall
import io.jeph.pipkin.agent.TimelineItem.TurnFooter
import io.jeph.pipkin.agent.TimelineItem.User
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSubagentTest {
  private val live = LiveSubagent()

  private val state get() = live.timeline.state.value

  private fun apply(at: Long, event: String) {
    live.apply(JSONObject().put("at", at).put("event", JSONObject(event)))
  }

  /** The log a screen gets on opening mid-run: the task, a call that ran, and a reply that streams. */
  private fun applyLogSoFar() {
    apply(1_000, """{"type":"agent_start"}""")
    apply(1_000, """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"go"}]}}""")
    apply(
      2_000,
      """{"type":"message_end","message":{"role":"assistant","content":[
        {"type":"toolCall","id":"t1","name":"bash","arguments":{"command":"sleep 60"}}]}}""",
    )
    apply(
      2_000,
      """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash","args":{"command":"sleep 60"}}""",
    )
    apply(
      2_500,
      """{"type":"tool_execution_update","toolCallId":"t1","partialResult":{"content":[{"type":"text","text":"tick"}]}}""",
    )
  }

  @Test
  fun aLogSentMidRunShowsTheRunWorkingAndTimedFromItsStart() {
    applyLogSoFar()

    assertTrue(state.busy)
    val user = state.items.first() as User
    assertEquals("go", user.text)
    val tool = state.items.filterIsInstance<ToolCall>().single()
    assertEquals(ToolStatus.Running, tool.status)
    assertEquals("tick", tool.output)
    assertEquals(TurnFooter(state.items.last().key, startedAt = 1_000, durationMillis = null), state.items.last())
  }

  @Test
  fun eventsAfterTheLogStreamTheReplyAndEndTheRunAtItsLastEventsTime() {
    applyLogSoFar()
    apply(
      4_000,
      """{"type":"tool_execution_end","toolCallId":"t1","toolName":"bash",
        "result":{"content":[{"type":"text","text":"tick tock"}]},"isError":false}""",
    )
    apply(4_000, """{"type":"message_start","message":{"role":"assistant","content":[]}}""")
    apply(
      4_100,
      """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Do"}}""",
    )

    val streaming = state.items.filterIsInstance<AssistantText>().single()
    assertEquals("Do", streaming.markdown)
    assertTrue(streaming.streaming)
    assertEquals(ToolStatus.Done, state.items.filterIsInstance<ToolCall>().single().status)

    apply(4_200, """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Done"}]}}""")
    apply(4_200, """{"type":"agent_end"}""")
    apply(9_000, """{"type":"agent_settled","aborted":false}""")

    assertFalse(state.busy)
    assertEquals("Done", state.items.filterIsInstance<AssistantText>().single().markdown)
    val footer = state.items.last() as TurnFooter
    assertEquals(1_000, footer.startedAt)
    assertEquals(8_000L, footer.durationMillis)
  }

  @Test
  fun onlyARunAScreenStillFollowsGetsATranscript() = runBlocking {
    val sent = mutableListOf<String>()
    val subagents = LiveSubagents(this) { sent += it.optString("action") }
    val start = """{"at":1,"event":{"type":"agent_start"}}"""
    subagents.follow("r1")

    subagents.onMessage(JSONObject("""{"live":{"id":"r2","entries":[$start]}}"""))
    assertNull(subagents.of("r2").first())
    subagents.onMessage(JSONObject("""{"live":{"id":"r1","entries":[$start]}}"""))
    val followed = checkNotNull(subagents.of("r1").first())
    assertTrue(followed.timeline.state.value.busy)
    subagents.onMessage(JSONObject("""{"event":{"id":"r1","at":5,"event":{"type":"agent_settled"}}}"""))
    assertFalse(followed.timeline.state.value.busy)

    // A log that crossed the screen closing stays out.
    subagents.unfollow("r1")
    subagents.onMessage(JSONObject("""{"live":{"id":"r1","entries":[$start]}}"""))
    assertNull(subagents.of("r1").first())
    yield()
    assertEquals(listOf("follow", "unfollow"), sent)
  }
}
