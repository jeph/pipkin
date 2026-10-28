package io.jeph.pipkin.localmodel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StreamProgressTest {
  private var now = 0L
  private val progress = StreamProgress { now }

  @Test
  fun `reads llama's prompt progress and strips it from what Pi gets`() {
    val sent = progress.onData(
      """{"choices":[{"index":0,"delta":{"role":"assistant","content":null}}],""" +
        """"prompt_progress":{"total":4300,"cache":3800,"processed":4100,"time_ms":900}}""",
    )
    assertEquals(SessionActivity.ReadingPrompt(processed = 4100, total = 4300, cached = 3800), progress.activity)
    assertFalse(JSONObject(sent).has("prompt_progress"))
  }

  @Test
  fun `counts written tokens and their pace from the first one`() {
    progress.onData(delta("""{"reasoning_content":"Let"}"""))
    now = 500
    progress.onData(delta("""{"content":"Hi"}"""))
    now = 1000
    progress.onData(delta("""{"tool_calls":[{"index":0,"function":{"arguments":"{"}}]}"""))
    assertEquals(SessionActivity.Writing(tokens = 3, tokensPerSecond = 2f), progress.activity)
  }

  @Test
  fun `empty deltas, the done marker and non-JSON lines pass through untouched`() {
    progress.onData(delta("""{"role":"assistant","content":null}"""))
    assertNull(progress.activity)
    assertEquals("[DONE]", progress.onData("[DONE]"))
    assertEquals("not json", progress.onData("not json"))
  }

  private fun delta(json: String) = """{"choices":[{"index":0,"delta":$json}]}"""
}
