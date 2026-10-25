package io.jeph.pipkin.agent

import io.jeph.pipkin.agent.TimelineItem.AssistantText
import io.jeph.pipkin.agent.TimelineItem.ToolCall
import io.jeph.pipkin.agent.TimelineItem.TurnFooter
import io.jeph.pipkin.agent.TimelineItem.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
  fun aToolCallStreamingInIsNamedAndShowsItsCommandBeforeTheMessageEnds() {
    apply(1_000, """{"type":"agent_start"}""")
    apply(1_000, """{"type":"message_start","message":{"role":"assistant","content":[]}}""")
    apply(
      1_100,
      """{"type":"message_update","assistantMessageEvent":
        {"type":"toolcall_start","contentIndex":0,"id":"call_1","toolName":"bash"}}""",
    )
    apply(
      1_200,
      """{"type":"message_update","assistantMessageEvent":
        {"type":"toolcall_delta","contentIndex":0,"delta":"{\"command\":\"ls -la\",\"time"}}""",
    )

    val call = state.items.filterIsInstance<ToolCall>().single()
    assertEquals("call_1", call.id)
    assertEquals("bash", call.toolName)
    assertEquals("ls -la", call.args?.optString("command"))
    assertTrue(call.argsStreaming)
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

  /** Followed run r1, with its log so far ended by [last], whose `unsteer` answers the extension sends [steering]. */
  private suspend fun followedAnswering(scope: CoroutineScope, steering: String, last: String): LiveSubagents {
    lateinit var subagents: LiveSubagents
    subagents = LiveSubagents(scope) { message ->
      if (message.optString("action") == "unsteer") {
        subagents.onMessage(JSONObject("""{"unsteered":{"id":"r1","steering":$steering}}"""))
      }
    }
    subagents.follow("r1")
    val log = """[{"at":1,"event":{"type":"agent_start"}},{"at":2,"event":$last}]"""
    subagents.onMessage(JSONObject("""{"live":{"id":"r1","entries":$log}}"""))
    return subagents
  }

  @Test
  fun steersTakenBackComeBackOldestFirst() = runBlocking {
    val tool = """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash","args":{}}"""
    val subagents = followedAnswering(this, """["one","two"]""", tool)
    assertEquals(listOf("one", "two"), subagents.takeSteers("r1"))
  }

  @Test
  fun steersPiTookForTheNextTurnAreLeftOut() = runBlocking {
    // A turn ended, so Pi may be handing the queue to the subagent's next turn as the steers are taken back.
    val subagents = followedAnswering(this, """["one","two"]""", """{"type":"turn_end"}""")
    val taken = async { subagents.takeSteers("r1") }
    yield()
    assertFalse(taken.isCompleted)
    val user = """{"role":"user","content":[{"type":"text","text":"one"}]}"""
    subagents.onMessage(JSONObject("""{"event":{"id":"r1","at":3,"event":{"type":"message_start","message":$user}}}"""))
    val reply = """{"role":"assistant","content":[]}"""
    subagents.onMessage(
      JSONObject("""{"event":{"id":"r1","at":4,"event":{"type":"message_start","message":$reply}}}"""),
    )
    assertEquals(listOf("two"), taken.await())
  }

  @Test
  fun aRunNoScreenFollowsHasNoSteersToTake() = runBlocking {
    val sent = mutableListOf<String>()
    val subagents = LiveSubagents(this) { sent += it.optString("action") }
    assertEquals(emptyList<String>(), subagents.takeSteers("r1"))
    assertEquals(emptyList<String>(), sent)
  }

  @Test
  fun aSecondEditWhileTheFirstWaitsTakesNothing() = runBlocking {
    val requests = mutableListOf<String>()
    val subagents = LiveSubagents(this) { if (it.optString("action") == "unsteer") requests += it.optString("id") }
    subagents.follow("r1")
    val tool = """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash","args":{}}"""
    subagents.onMessage(JSONObject("""{"live":{"id":"r1","entries":[{"at":1,"event":$tool}]}}"""))
    val first = async { subagents.takeSteers("r1") }
    val second = async { subagents.takeSteers("r1") }
    yield()
    // The extension hands the steers to the first request it gets; any later one finds the queue empty.
    subagents.onMessage(JSONObject("""{"unsteered":{"id":"r1","steering":["one","two"]}}"""))
    if (requests.size > 1) subagents.onMessage(JSONObject("""{"unsteered":{"id":"r1","steering":[]}}"""))
    assertEquals(listOf("one", "two"), first.await() + second.await())
    assertEquals(listOf("r1"), requests)
  }
}
