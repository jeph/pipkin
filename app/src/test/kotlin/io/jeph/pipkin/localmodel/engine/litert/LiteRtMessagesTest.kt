package io.jeph.pipkin.localmodel.engine.litert

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolCall as LiteRtToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtMessagesTest {
  private val calls = ChatMessage.Assistant(
    "Reading both.",
    listOf(
      ToolCall("1", "read", """{"path":"a","range":[1,2],"opts":{"raw":true,"enc":null}}"""),
      ToolCall("2", "ls", "{}"),
    ),
  )

  @Test
  fun `history keeps each message, with every tool result its own`() {
    val history = historyMessages(
      listOf(
        ChatMessage.User(listOf("Hi ", "there")),
        ChatMessage.System("Now in French."),
        calls,
        ChatMessage.ToolResult("1", "read", "a"),
        ChatMessage.ToolResult("2", "ls", "b"),
      ),
    )

    assertEquals(listOf(Role.USER, Role.SYSTEM, Role.MODEL, Role.TOOL, Role.TOOL), history.map { it.role })
    assertEquals(listOf(Content.Text("Hi "), Content.Text("there")), history[0].contents.contents)
    assertEquals(listOf(Content.Text("Reading both.")), history[2].contents.contents)
    assertEquals(
      listOf(
        LiteRtToolCall(
          "read",
          mapOf(
            "path" to "a",
            "range" to listOf(1, 2),
            "opts" to mapOf("raw" to true, "enc" to null),
          ),
        ),
        LiteRtToolCall("ls", emptyMap()),
      ),
      history[2].toolCalls,
    )
    assertEquals(listOf(Content.ToolResponse("read", "a")), history[3].contents.contents)
    assertEquals(listOf(Content.ToolResponse("ls", "b")), history[4].contents.contents)
  }

  @Test
  fun `a reply with only tool calls has no text`() {
    val message = historyMessages(listOf(ChatMessage.Assistant("", listOf(ToolCall("1", "ls", "{}"))))).single()

    assertTrue(message.contents.contents.isEmpty())
  }

  @Test
  fun `parallel tool results go in one tool message`() {
    val input = inputMessage(listOf(ChatMessage.ToolResult("1", "read", "a"), ChatMessage.ToolResult("2", "ls", "b")))

    assertEquals(Role.TOOL, input.role)
    assertEquals(listOf(Content.ToolResponse("read", "a"), Content.ToolResponse("ls", "b")), input.contents.contents)
  }

  @Test
  fun `a user turn is sent as a user message`() {
    val input = inputMessage(listOf(ChatMessage.User(listOf("Thanks"))))

    assertEquals(Role.USER, input.role)
    assertEquals(listOf(Content.Text("Thanks")), input.contents.contents)
  }

  @Test
  fun `arguments round-trip to the JSON an OpenAI tool call carries`() {
    val arguments = """{"opts":{"enc":null,"raw":true},"path":"a","range":[1,2.5]}"""

    assertEquals(arguments, argumentsJson(argumentsMap(arguments)))
    assertEquals(emptyMap<String, Any?>(), argumentsMap("[1]"))
  }

  @Test
  fun `tools are described by their function object and never run here`() {
    val function = """{"name":"read","parameters":{"type":"object"}}"""
    val tool = FunctionTool(function)

    assertEquals("read", JSONObject(tool.getToolDescriptionJsonString()).getString("name"))
    assertThrows(UnsupportedOperationException::class.java) { tool.execute("{}") }
  }
}
