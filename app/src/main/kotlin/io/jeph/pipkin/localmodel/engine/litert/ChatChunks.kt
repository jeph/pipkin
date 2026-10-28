package io.jeph.pipkin.localmodel.engine.litert

import org.json.JSONArray
import org.json.JSONObject

/**
 * The model's reply as it streams in. It turns each piece LiteRT-LM hands over into an OpenAI delta, and keeps the
 * whole reply so the live [Transcript] can record what the model said for the next request to be compared against.
 */
internal class Reply(private val newCallId: () -> String) {
  private val text = StringBuilder()
  private val reasoning = StringBuilder()
  private val calls = mutableListOf<ToolCall>()
  private var started = false

  val hasToolCalls: Boolean get() = calls.isNotEmpty()

  /**
   * Adds one streamed piece: reply text, reasoning, and complete tool calls as name and JSON arguments (LiteRT-LM
   * parses a call only once the model has closed it). Returns its delta, or null when the piece carries nothing.
   */
  fun add(textPiece: String, reasoningPiece: String, toolCalls: List<Pair<String, String>>): JSONObject? {
    if (textPiece.isEmpty() && reasoningPiece.isEmpty() && toolCalls.isEmpty()) return null
    val delta = JSONObject()
    if (!started) delta.put("role", "assistant")
    started = true
    if (reasoningPiece.isNotEmpty()) {
      reasoning.append(reasoningPiece)
      delta.put("reasoning_content", reasoningPiece)
    }
    if (textPiece.isNotEmpty()) {
      text.append(textPiece)
      delta.put("content", textPiece)
    }
    if (toolCalls.isNotEmpty()) {
      val deltas = JSONArray()
      for ((name, arguments) in toolCalls) {
        val call = ToolCall(newCallId(), name, arguments)
        deltas.put(toolCallJson(call).put("index", calls.size))
        calls += call
      }
      delta.put("tool_calls", deltas)
    }
    return delta
  }

  fun finishReason(lengthReached: Boolean): String = when {
    hasToolCalls -> "tool_calls"
    lengthReached -> "length"
    else -> "stop"
  }

  /** The reply as the conversation now holds it. */
  fun message(): ChatMessage.Assistant = ChatMessage.Assistant(text.toString(), calls.toList())

  /** The non-streaming response's `message`. */
  fun json(): JSONObject = JSONObject()
    .put("role", "assistant")
    .put("content", if (text.isEmpty() && hasToolCalls) JSONObject.NULL else text.toString())
    .apply {
      if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
      if (hasToolCalls) put("tool_calls", JSONArray(calls.map(::toolCallJson)))
    }

  private fun toolCallJson(call: ToolCall): JSONObject = JSONObject()
    .put("id", call.id)
    .put("type", "function")
    .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
}

/**
 * Token counts for one reply; [cached] are prompt tokens the conversation already held from earlier turns, null when
 * LiteRT-LM re-read part of them and can't say how many.
 */
internal data class Usage(val prompt: Int, val completion: Int, val cached: Int?) {
  fun json(): JSONObject = JSONObject()
    .put("prompt_tokens", prompt)
    .put("completion_tokens", completion)
    .put("total_tokens", prompt + completion)
    .apply { if (cached != null) put("prompt_tokens_details", JSONObject().put("cached_tokens", cached)) }
}

/** OpenAI Chat Completions bodies for one completion [id]. */
internal class Completion(private val id: String, private val created: Long, private val model: String) {
  /** One `chat.completion.chunk` with [delta], as an SSE event. */
  fun chunk(delta: JSONObject, finishReason: String? = null): String {
    val choice = JSONObject()
      .put("index", 0)
      .put("delta", delta)
      .put("finish_reason", finishReason ?: JSONObject.NULL)
    return event(base("chat.completion.chunk").put("choices", JSONArray().put(choice)))
  }

  /** The last chunk when the client asked for `stream_options.include_usage`. */
  fun usageChunk(usage: Usage): String =
    event(base("chat.completion.chunk").put("choices", JSONArray()).put("usage", usage.json()))

  /** The whole non-streaming response. */
  fun response(reply: Reply, finishReason: String, usage: Usage?): String {
    val choice = JSONObject().put("index", 0).put("message", reply.json()).put("finish_reason", finishReason)
    return base("chat.completion")
      .put("choices", JSONArray().put(choice))
      .apply { if (usage != null) put("usage", usage.json()) }
      .toString()
  }

  private fun base(type: String): JSONObject =
    JSONObject().put("id", id).put("object", type).put("created", created).put("model", model)

  companion object {
    const val DONE = "data: [DONE]\n\n"

    fun event(json: JSONObject): String = "data: $json\n\n"

    fun error(message: String, type: String = "server_error"): String =
      event(JSONObject().put("error", JSONObject().put("message", message).put("type", type)))
  }
}
