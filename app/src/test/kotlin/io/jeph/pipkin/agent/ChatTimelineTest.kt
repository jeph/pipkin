package io.jeph.pipkin.agent

import io.jeph.pipkin.agent.TimelineItem.AssistantText
import io.jeph.pipkin.agent.TimelineItem.ExitBanner
import io.jeph.pipkin.agent.TimelineItem.Notice
import io.jeph.pipkin.agent.TimelineItem.Thinking
import io.jeph.pipkin.agent.TimelineItem.ToolCall
import io.jeph.pipkin.agent.TimelineItem.TurnFooter
import io.jeph.pipkin.agent.TimelineItem.User
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {
  private var now = 1_000L
  private val timeline = ChatTimeline { now }

  private val items get() = timeline.state.value.items

  @Test
  fun seedDuringAnOpenRunReplacesItWithTheSettledHistory() {
    apply("""{"type":"agent_start"}""")
    apply("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
    update("""{"type":"text_delta","contentIndex":0,"delta":"Work"}""")

    timeline.seed(JSONArray("""[{"role":"user","content":"go","timestamp":5}]"""))
    assertFalse(timeline.state.value.busy)
    assertEquals(listOf(User("m1", "go", emptyList(), 5)), items)

    // A settle that was still on its way ends nothing more; the next run opens a fresh footer.
    apply("""{"type":"agent_settled","aborted":false}""")
    assertEquals(listOf(User("m1", "go", emptyList(), 5)), items)
    apply("""{"type":"agent_start"}""")
    assertTrue(timeline.state.value.busy)
    assertEquals(TurnFooter("t1", 1_000, null), items.last())
  }

  @Test
  fun guardedSeedKeepsAStreamThatMovedOn() {
    apply("""{"type":"agent_start"}""")
    val since = timeline.appliedEvents
    val history = JSONArray("""[{"role":"user","content":"go","timestamp":5}]""")

    // A new message arrived while the history was being fetched, so the live stream wins.
    apply("""{"type":"message_start","message":{"role":"user","content":"next","timestamp":9}}""")
    assertFalse(timeline.seed(history, ifNoEventsSince = since))
    assertTrue(timeline.state.value.busy)
    assertEquals(listOf("next"), items.filterIsInstance<User>().map { it.text })

    assertTrue(timeline.seed(history, ifNoEventsSince = timeline.appliedEvents))
    assertFalse(timeline.state.value.busy)
    assertEquals(listOf(User("m1", "go", emptyList(), 5)), items)
  }

  @Test
  fun seedMapsEveryRoleAndAttachesToolResults() {
    timeline.seed(
      JSONArray(
        """
        [
          {"role":"user","content":"hi","timestamp":5},
          {"role":"assistant","stopReason":"toolUse","content":[
            {"type":"thinking","thinking":"hmm"},
            {"type":"text","text":"Listing"},
            {"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"ls"}}
          ]},
          {"role":"toolResult","toolCallId":"c1","toolName":"bash","isError":true,
            "content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]},
          {"role":"assistant","stopReason":"error","errorMessage":"boom","content":[]},
          {"role":"bashExecution","command":"pwd","output":"/home","exitCode":0,"cancelled":false},
          {"role":"compactionSummary","summary":"ignored"},
          {"role":"user","timestamp":6,"content":[
            {"type":"text","text":"look"},
            {"type":"image","data":"AAA","mimeType":"image/png"}
          ]}
        ]
        """,
      ),
    )

    assertEquals(User("m0", "hi", emptyList(), 5), items[0])
    assertEquals(Thinking("m1.0", "hmm", streaming = false), items[1])
    assertEquals(AssistantText("m1.1", "Listing", streaming = false), items[2])
    val tool = items[3] as ToolCall
    assertEquals("c1", tool.id)
    assertEquals("ls", tool.args?.getString("command"))
    assertEquals("a\nb", tool.output)
    assertTrue(tool.isError)
    assertEquals(ToolStatus.Done, tool.status)
    assertEquals(TimelineItem.Error("m2.error", "boom"), items[4])
    val bash = items[5] as ToolCall
    assertEquals("bash", bash.toolName)
    assertEquals("pwd", bash.args?.getString("command"))
    assertEquals("/home", bash.output)
    assertFalse(bash.isError)
    assertEquals(User("m4", "look", listOf(ImagePart("AAA", "image/png")), 6), items[6])
    assertEquals(7, items.size)
  }

  @Test
  fun expandedSkillShowsAsTheTypedCommand() {
    val block = "<skill name=\"haiku\" location=\"/sdcard/Pipkin/pi/skills/haiku/SKILL.md\">\nWrite one.\n</skill>"
    val messages = JSONArray()
      .put(JSONObject().put("role", "user").put("timestamp", 5).put("content", "$block\n\nthe sea\nat dawn"))
      .put(JSONObject().put("role", "user").put("timestamp", 6).put("content", block))
      .put(JSONObject().put("role", "user").put("timestamp", 7).put("content", "quote: $block"))
    timeline.seed(messages)

    assertEquals(
      listOf("/skill:haiku the sea\nat dawn", "/skill:haiku", "quote: $block"),
      items.map {
        (it as User).text
      },
    )
  }

  @Test
  fun streamedBlocksKeepKeysThroughMessageEnd() = streamToolCallTurn()

  @Test
  fun toolExecutionReplacesCumulativeOutputAndSettleClosesFooter() {
    streamToolCallTurn()
    apply("""{"type":"tool_execution_start","toolCallId":"c1","toolName":"bash","args":{"command":"ls"}}""")
    assertEquals(ToolStatus.Running, (items[3] as ToolCall).status)
    toolUpdate("a")
    assertEquals("a", (items[3] as ToolCall).output)
    toolUpdate("a\\nb")
    assertEquals("a\nb", (items[3] as ToolCall).output)
    apply(
      """{"type":"tool_execution_end","toolCallId":"c1","toolName":"bash","isError":false,
        "result":{"content":[{"type":"text","text":"a\nb\nc"}]}}""",
    )
    val toolResult =
      """{"role":"toolResult","toolCallId":"c1","toolName":"bash","isError":false,
        "content":[{"type":"text","text":"a\nb\nc"}]}"""
    apply("""{"type":"message_start","message":$toolResult}""")
    apply("""{"type":"message_end","message":$toolResult}""")
    val tool = items[3] as ToolCall
    assertEquals("a\nb\nc", tool.output)
    assertEquals(ToolStatus.Done, tool.status)
    assertFalse(tool.isError)

    apply("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
    update("""{"type":"text_delta","contentIndex":0,"delta":"Done"}""")
    assertEquals(AssistantText("m2.0", "Done", streaming = true), items[4])
    val done = """{"role":"assistant","stopReason":"stop","content":[{"type":"text","text":"Done."}]}"""
    apply("""{"type":"message_end","message":$done}""")
    apply("""{"type":"agent_end","messages":[],"willRetry":false}""")
    assertTrue(timeline.state.value.busy)

    now = 4_000
    apply("""{"type":"agent_settled","aborted":false}""")
    assertFalse(timeline.state.value.busy)
    assertEquals(AssistantText("m2.0", "Done.", streaming = false), items[4])
    assertEquals(TurnFooter("t0", 1_000, 3_000), items.last())
    assertEquals(User("m0", "do it", emptyList(), 7), items.first())
    assertEquals(6, items.size)
  }

  @Test
  fun settledRunStopsUnfinishedToolCalls() {
    apply("""{"type":"agent_start"}""")
    apply("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
    update("""{"type":"toolcall_start","contentIndex":0,"id":"c9","toolName":"read"}""")
    apply("""{"type":"agent_settled","aborted":true}""")
    assertEquals(ToolStatus.Done, (items[0] as ToolCall).status)
  }

  @Test
  fun streamingToolArgumentsParseOnlyUntilTheRowArgumentIsFinal() {
    apply("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
    update("""{"type":"toolcall_start","contentIndex":0,"id":"c2","toolName":"write"}""")
    assertTrue((items[0] as ToolCall).argsStreaming)
    assertEquals("", timeline.streamingArguments("m0.0"))
    update("""{"type":"toolcall_delta","contentIndex":0,"delta":"{\"path\":\"a.p"}""")
    assertEquals("a.p", (items[0] as ToolCall).args?.getString("path"))
    // A closed path is still the last key, so it is parsed once more when the next key starts.
    update("""{"type":"toolcall_delta","contentIndex":0,"delta":"y\""}""")
    update("""{"type":"toolcall_delta","contentIndex":0,"delta":",\"content\":\"de"}""")
    assertEquals("a.py", (items[0] as ToolCall).args?.getString("path"))
    update("""{"type":"toolcall_delta","contentIndex":0,"delta":"f f():\\n"}""")
    assertEquals("a.py", (items[0] as ToolCall).args?.getString("path"))
    assertEquals("""{"path":"a.py","content":"def f():\n""", timeline.streamingArguments("m0.0"))

    val call = """{"type":"toolCall","id":"c2","name":"write","arguments":{"path":"a.py","content":"done"}}"""
    update("""{"type":"toolcall_end","contentIndex":0,"toolCall":$call}""")
    val tool = items[0] as ToolCall
    assertEquals("done", tool.args?.getString("content"))
    assertFalse(tool.argsStreaming)
    assertNull(timeline.streamingArguments("m0.0"))
  }

  @Test
  fun aBashCommandKeepsParsingWhileItIsTheLastKey() {
    apply("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
    update("""{"type":"toolcall_start","contentIndex":0,"id":"c3","toolName":"bash"}""")
    update("""{"type":"toolcall_delta","contentIndex":0,"delta":"{\"command\":\"ls"}""")
    update("""{"type":"toolcall_delta","contentIndex":0,"delta":" -la"}""")
    assertEquals("ls -la", (items[0] as ToolCall).args?.getString("command"))
  }

  @Test
  fun queueUpdateCombinesSteeringAndFollowUp() {
    apply("""{"type":"queue_update","steering":["now"],"followUp":["later","after"]}""")
    assertEquals(listOf("now", "later", "after"), timeline.state.value.queue)
    apply("""{"type":"queue_update","steering":[],"followUp":[]}""")
    assertEquals(emptyList<String>(), timeline.state.value.queue)
  }

  @Test
  fun extensionUiDialogsArePendingAndFireAndForgetBecomeEffects() = runBlocking {
    apply(
      """{"type":"extension_ui_request","id":"u1","method":"select","title":"Allow?","options":["Allow","Block"],
          "timeout":10000}""",
    )
    assertEquals(
      ExtensionUiRequest("u1", "select", "Allow?", null, listOf("Allow", "Block"), null, null),
      timeline.state.value.pendingUi,
    )
    timeline.clearPendingUi()
    assertNull(timeline.state.value.pendingUi)

    apply("""{"type":"extension_ui_request","id":"u2","method":"editor","title":"Edit","prefill":"x\ny"}""")
    assertEquals("x\ny", timeline.state.value.pendingUi?.prefill)
    timeline.clearPendingUi()

    apply("""{"type":"extension_ui_request","id":"u3","method":"setStatus","statusKey":"k","statusText":"s"}""")
    apply("""{"type":"extension_ui_request","id":"u4","method":"notify","message":"Nope","notifyType":"warning"}""")
    apply("""{"type":"extension_ui_request","id":"u5","method":"set_editor_text","text":"draft"}""")
    assertNull(timeline.state.value.pendingUi)
    val effects = withTimeout(1_000) { timeline.effects.take(2).toList() }
    assertEquals(
      listOf(TimelineEffect.Notify("Nope", "warning"), TimelineEffect.SetEditorText("draft")),
      effects,
    )
  }

  @Test
  fun retryAndCompactionNotices() {
    apply("""{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"529"}""")
    apply("""{"type":"auto_retry_start","attempt":2,"maxAttempts":3,"delayMs":2000,"errorMessage":"529"}""")
    assertEquals(listOf(Notice("n0", "Retrying…")), items)
    apply("""{"type":"auto_retry_end","success":true,"attempt":2}""")
    assertEquals(emptyList<TimelineItem>(), items)

    apply("""{"type":"compaction_start","reason":"threshold"}""")
    assertEquals(listOf(Notice("n1", "Compacting context…")), items)
    apply("""{"type":"compaction_end","reason":"threshold","result":{"summary":"s"},"aborted":false}""")
    assertEquals(listOf(Notice("n1", "Context compacted")), items)
  }

  @Test
  fun modelThinkingLevelAndExit() {
    val model = JSONObject("""{"provider":"anthropic","id":"claude","input":["text","image"]}""")
    timeline.setModel(model)
    timeline.setThinkingLevel("high")
    assertEquals(model, timeline.state.value.model)
    apply("""{"type":"thinking_level_changed","level":"low"}""")
    assertEquals("low", timeline.state.value.thinkingLevel)

    apply("""{"type":"agent_start"}""")
    now = 2_500
    timeline.showExit(1, "crash")
    assertFalse(timeline.state.value.busy)
    assertEquals(listOf(TurnFooter("t0", 1_000, 1_500), ExitBanner("x1", 1, "crash")), items)
  }

  @Test
  fun compactionKeepsTheChatBusyUntilItEnds() {
    apply("""{"type":"compaction_start","reason":"manual"}""")
    assertTrue(timeline.state.value.busy)
    apply("""{"type":"compaction_end","reason":"manual","aborted":false,"result":{"summary":"s"}}""")
    assertFalse(timeline.state.value.busy)

    apply("""{"type":"agent_start"}""")
    apply("""{"type":"compaction_start","reason":"threshold"}""")
    apply("""{"type":"compaction_end","reason":"threshold","aborted":true}""")
    assertTrue(timeline.state.value.busy)
    apply("""{"type":"agent_settled"}""")
    assertFalse(timeline.state.value.busy)

    apply("""{"type":"compaction_start","reason":"manual"}""")
    timeline.showExit(1, "crash")
    assertFalse(timeline.state.value.busy)
  }

  @Test
  fun failingExtensionCommandShowsItsError() {
    apply("""{"type":"extension_error","extensionPath":"command:boom","event":"command","error":"kaput"}""")

    assertEquals(listOf(TimelineItem.Error("e0", "Extension \"command:boom\" error: kaput")), items)
    assertFalse(timeline.state.value.busy)
  }

  private fun apply(json: String) = timeline.apply(JSONObject(json))

  private fun update(event: String) = apply("""{"type":"message_update","assistantMessageEvent":$event}""")

  private fun toolUpdate(text: String) = apply(
    """{"type":"tool_execution_update","toolCallId":"c1","toolName":"bash","args":{},
        "partialResult":{"content":[{"type":"text","text":"$text"}]}}""",
  )

  /** Streams a run with a user message and an assistant message holding thinking, text and a bash call `c1`. */
  private fun streamToolCallTurn() {
    apply("""{"type":"agent_start"}""")
    assertTrue(timeline.state.value.busy)
    assertEquals(listOf(TurnFooter("t0", 1_000, null)), items)

    val user = """{"role":"user","content":"do it","timestamp":7}"""
    apply("""{"type":"message_start","message":$user}""")
    apply("""{"type":"message_end","message":$user}""")
    apply("""{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""")
    update("""{"type":"thinking_start","contentIndex":0}""")
    update("""{"type":"thinking_delta","contentIndex":0,"delta":"Let me"}""")
    update("""{"type":"thinking_delta","contentIndex":0,"delta":" think"}""")
    assertEquals(Thinking("m1.0", "Let me think", streaming = true), items[1])
    update("""{"type":"thinking_end","contentIndex":0,"content":"Let me think"}""")
    update("""{"type":"text_start","contentIndex":1}""")
    update("""{"type":"text_delta","contentIndex":1,"delta":"Run"}""")
    assertEquals(AssistantText("m1.1", "Run", streaming = true), items[2])
    update("""{"type":"toolcall_start","contentIndex":2,"id":"c1","toolName":"bash"}""")
    assertEquals(ToolStatus.Pending, (items[3] as ToolCall).status)
    update("""{"type":"toolcall_delta","contentIndex":2,"delta":"{\"comm"}""")
    val call = """{"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"ls"}}"""
    update("""{"type":"toolcall_end","contentIndex":2,"toolCall":$call}""")
    assertEquals("ls", (items[3] as ToolCall).args?.getString("command"))
    val streamedKeys = items.map { it.key }

    val final =
      """{"role":"assistant","stopReason":"toolUse","content":[
        {"type":"thinking","thinking":"Let me think"},{"type":"text","text":"Running ls"},$call]}"""
    apply("""{"type":"message_end","message":$final}""")
    assertEquals(streamedKeys, items.map { it.key })
    assertEquals(listOf("m0", "m1.0", "m1.1", "m1.2", "t0"), streamedKeys)
    assertEquals(AssistantText("m1.1", "Running ls", streaming = false), items[2])
    assertEquals(Thinking("m1.0", "Let me think", streaming = false), items[1])
  }
}
