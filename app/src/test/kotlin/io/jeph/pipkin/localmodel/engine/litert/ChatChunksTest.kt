package io.jeph.pipkin.localmodel.engine.litert

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatChunksTest {
  private var nextId = 0
  private val reply = Reply { "call_${nextId++}" }
  private val completion = Completion("chatcmpl-1", 1_700_000_000, "qwen3-0.6b")

  private fun data(event: String): JSONObject {
    assertTrue(event.startsWith("data: ") && event.endsWith("\n\n"))
    return JSONObject(event.removePrefix("data: ").trim())
  }

  @Test
  fun `deltas carry reasoning, text and tool calls with fresh ids`() {
    val first = reply.add("", "Thinking", emptyList())!!
    val second = reply.add("Let me look.", "", emptyList())!!
    val third = reply.add("", "", listOf("read" to """{"path":"a"}""", "ls" to "{}"))!!
    val fourth = reply.add("", "", listOf("read" to "{}"))!!

    assertEquals("assistant", first.getString("role"))
    assertEquals("Thinking", first.getString("reasoning_content"))
    assertFalse(second.has("role"))
    assertEquals("Let me look.", second.getString("content"))
    val calls = third.getJSONArray("tool_calls")
    assertEquals(0, calls.getJSONObject(0).getInt("index"))
    assertEquals("call_0", calls.getJSONObject(0).getString("id"))
    assertEquals("function", calls.getJSONObject(0).getString("type"))
    assertEquals("read", calls.getJSONObject(0).getJSONObject("function").getString("name"))
    assertEquals("""{"path":"a"}""", calls.getJSONObject(0).getJSONObject("function").getString("arguments"))
    assertEquals(1, calls.getJSONObject(1).getInt("index"))
    assertEquals(2, fourth.getJSONArray("tool_calls").getJSONObject(0).getInt("index"))
    assertNull(reply.add("", "", emptyList()))
    assertEquals(
      ChatMessage.Assistant(
        "Let me look.",
        listOf(
          ToolCall("call_0", "read", """{"path":"a"}"""),
          ToolCall("call_1", "ls", "{}"),
          ToolCall("call_2", "read", "{}"),
        ),
      ),
      reply.message(),
    )
    assertEquals("tool_calls", reply.finishReason(lengthReached = true))
  }

  @Test
  fun `finish reasons without tool calls`() {
    reply.add("Hi", "", emptyList())

    assertEquals("stop", reply.finishReason(lengthReached = false))
    assertEquals("length", reply.finishReason(lengthReached = true))
  }

  @Test
  fun `chunks are OpenAI chat completion chunks`() {
    val chunk = data(completion.chunk(JSONObject().put("content", "Hi")))
    val last = data(completion.chunk(JSONObject(), "stop"))

    assertEquals("chatcmpl-1", chunk.getString("id"))
    assertEquals("chat.completion.chunk", chunk.getString("object"))
    assertEquals(1_700_000_000L, chunk.getLong("created"))
    assertEquals("qwen3-0.6b", chunk.getString("model"))
    val choice = chunk.getJSONArray("choices").getJSONObject(0)
    assertEquals(0, choice.getInt("index"))
    assertEquals("Hi", choice.getJSONObject("delta").getString("content"))
    assertTrue(choice.isNull("finish_reason"))
    assertEquals("stop", last.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
  }

  @Test
  fun `the usage chunk has no choices`() {
    val usage = data(completion.usageChunk(Usage(prompt = 120, completion = 30, cached = 100)))

    assertEquals(0, usage.getJSONArray("choices").length())
    val counts = usage.getJSONObject("usage")
    assertEquals(120, counts.getInt("prompt_tokens"))
    assertEquals(30, counts.getInt("completion_tokens"))
    assertEquals(150, counts.getInt("total_tokens"))
    assertEquals(100, counts.getJSONObject("prompt_tokens_details").getInt("cached_tokens"))
  }

  @Test
  fun `the non-streaming response holds the whole reply`() {
    reply.add("", "Hmm", emptyList())
    reply.add("", "", listOf("ls" to "{}"))

    val response = JSONObject(completion.response(reply, "tool_calls", Usage(10, 5, cached = null)))

    assertEquals("chat.completion", response.getString("object"))
    val choice = response.getJSONArray("choices").getJSONObject(0)
    assertEquals("tool_calls", choice.getString("finish_reason"))
    val message = choice.getJSONObject("message")
    assertTrue(message.isNull("content"))
    assertEquals("Hmm", message.getString("reasoning_content"))
    assertEquals("ls", message.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
    val usage = response.getJSONObject("usage")
    assertEquals(15, usage.getInt("total_tokens"))
    assertFalse("an unknown cached count is left out", usage.has("prompt_tokens_details"))
  }

  @Test
  fun `errors and the end of the stream`() {
    val error = data(Completion.error("boom")).getJSONObject("error")

    assertEquals("boom", error.getString("message"))
    assertEquals("server_error", error.getString("type"))
    assertEquals("data: [DONE]\n\n", Completion.DONE)
  }
}
