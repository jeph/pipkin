package io.jeph.pipkin.localmodel.engine.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestTest {
  @Test
  fun `parses pi-ai's request with tools, tool calls and tool results`() {
    val request = parseChatRequest(
      """
      {
        "model": "qwen3-0.6b",
        "stream": true,
        "stream_options": {"include_usage": true},
        "max_completion_tokens": 512,
        "reasoning_effort": "none",
        "return_progress": true,
        "messages": [
          {"role": "developer", "content": "Be brief."},
          {"role": "user", "content": [{"type": "text", "text": "Read "}, {"type": "text", "text": "a.txt"}]},
          {"role": "assistant", "content": null, "tool_calls": [
            {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{\"path\": \"a.txt\", \"limit\": 2}"}}
          ]},
          {"role": "tool", "tool_call_id": "call_1", "content": "hello"}
        ],
        "tools": [{"type": "function", "function": {"name": "read", "description": "Read a file",
          "parameters": {"type": "object", "properties": {"path": {"type": "string"}}}}}]
      }
      """,
    )

    assertEquals("Be brief.", request.setup.system)
    assertEquals(
      listOf(
        """{"description":"Read a file","name":"read","parameters":{"properties":{"path":{"type":"string"}},"type":"object"}}""",
      ),
      request.setup.tools,
    )
    assertNull(request.setup.temperature)
    assertEquals(
      listOf(
        ChatMessage.User(listOf("Read ", "a.txt")),
        ChatMessage.Assistant("", listOf(ToolCall("call_1", "read", """{"limit":2,"path":"a.txt"}"""))),
        ChatMessage.ToolResult("call_1", "read", "hello"),
      ),
      request.messages,
    )
    assertTrue(request.stream)
    assertTrue(request.includeUsage)
    assertEquals(512, request.maxTokens)
    assertFalse(request.thinking)
  }

  @Test
  fun `defaults leave thinking on and the model's own limits`() {
    val request =
      parseChatRequest("""{"messages": [{"role": "user", "content": "Hi"}], "temperature": 0.2, "max_tokens": 0}""")

    assertNull(request.setup.system)
    assertEquals(0.2, request.setup.temperature!!, 0.0)
    assertFalse(request.stream)
    assertFalse(request.includeUsage)
    assertNull(request.maxTokens)
    assertTrue(request.thinking)
  }

  @Test
  fun `a later system message stays in the history`() {
    val request = parseChatRequest(
      """{"messages": [{"role": "user", "content": "Hi"}, {"role": "system", "content": "Now in French."}]}""",
    )

    assertNull(request.setup.system)
    assertEquals(listOf(ChatMessage.User(listOf("Hi")), ChatMessage.System("Now in French.")), request.messages)
  }

  @Test
  fun `rejects what LiteRT-LM can't run`() {
    val image = """{"messages": [{"role": "user", "content": [{"type": "image_url", "image_url": {"url": "x"}}]}]}"""
    val orphan = """{"messages": [{"role": "tool", "tool_call_id": "nope", "content": "x"}]}"""

    assertThrows(BadRequestException::class.java) { parseChatRequest("not json") }
    assertThrows(BadRequestException::class.java) { parseChatRequest("{}") }
    assertThrows(BadRequestException::class.java) { parseChatRequest(image) }
    assertThrows(BadRequestException::class.java) { parseChatRequest(orphan) }
    assertThrows(BadRequestException::class.java) {
      parseChatRequest("""{"messages": [{"role": "user", "content": "x"}], "tools": [{"type": "custom"}]}""")
    }
  }

  @Test
  fun `arguments that aren't JSON are kept as sent`() {
    val request = parseChatRequest(
      """
      {"messages": [{"role": "assistant", "content": "", "tool_calls": [
        {"id": "c", "type": "function", "function": {"name": "f", "arguments": "{oops"}}]}]}
      """,
    )

    assertEquals(ChatMessage.Assistant("", listOf(ToolCall("c", "f", "{oops"))), request.messages.single())
  }

  @Test
  fun `canonical JSON doesn't depend on key order or spacing`() {
    assertEquals(
      canonicalJson(org.json.JSONObject("""{"b": [1, {"y": null, "x": "s"}], "a": true}""")),
      canonicalJson(org.json.JSONObject("""{"a":true,"b":[1,{"x":"s","y":null}]}""")),
    )
  }
}
