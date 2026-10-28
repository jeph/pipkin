package io.jeph.pipkin.localmodel

import org.json.JSONException
import org.json.JSONObject

/**
 * Follows one streamed chat completion through its chunks for the chat's status line: llama.cpp's `prompt_progress`
 * while the prompt is read, then the pace of the reply. Chunks pass on to Pi without `prompt_progress`, which the
 * proxy asks for on Pi's behalf.
 */
internal class StreamProgress(private val now: () -> Long = System::currentTimeMillis) {
  private var firstTokenAt = 0L
  private var tokens = 0

  /** What the request is doing after the last chunk; null until the first one says. */
  var activity: SessionActivity? = null
    private set

  /** Reads one SSE `data:` payload and returns what to send Pi in its place. */
  fun onData(payload: String): String {
    if (payload == DONE) return payload
    val chunk = try {
      JSONObject(payload)
    } catch (_: JSONException) {
      return payload
    }
    val progress = chunk.remove("prompt_progress") as? JSONObject
    if (progress != null) {
      activity = SessionActivity.ReadingPrompt(
        processed = progress.optInt("processed"),
        total = progress.optInt("total"),
        cached = progress.optInt("cache"),
      )
    }
    if (writes(chunk)) {
      val time = now()
      if (tokens == 0) firstTokenAt = time
      tokens += 1
      val seconds = (time - firstTokenAt) / MILLIS_PER_SECOND
      activity = SessionActivity.Writing(tokens, if (seconds > 0) (tokens - 1) / seconds else 0f)
    }
    return chunk.toString()
  }

  /** Whether the chunk carries reply text, reasoning or tool call arguments: about one token each from llama.cpp. */
  private fun writes(chunk: JSONObject): Boolean {
    val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return false
    return !(delta.opt("content") as? String).isNullOrEmpty() ||
      !(delta.opt("reasoning_content") as? String).isNullOrEmpty() ||
      (delta.optJSONArray("tool_calls")?.length() ?: 0) > 0
  }

  private companion object {
    const val DONE = "[DONE]"
    const val MILLIS_PER_SECOND = 1000f
  }
}
