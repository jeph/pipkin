package io.jeph.pipkin.localmodel.engine.litert

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The parts of an OpenAI Chat Completions request that LiteRT-LM can act on, in a form that compares by value: Pi
 * resends the whole history on every request, and [planTurn] decides by comparing it with what the live conversation
 * has already seen whether the model's cache still holds it.
 */
internal data class ChatRequest(
  val setup: ConversationSetup,
  /** Every message after the system prompt, oldest first. */
  val messages: List<ChatMessage>,
  val stream: Boolean,
  val includeUsage: Boolean,
  val maxTokens: Int?,
  /** False when `reasoning_effort` is "none", which turns a thinking model's reasoning off for this reply. */
  val thinking: Boolean,
)

/** What a LiteRT-LM conversation is created with, so a request with a different one needs a new conversation. */
internal data class ConversationSetup(
  val system: String?,
  /** Each tool's `function` object as canonical JSON (see [canonicalJson]). */
  val tools: List<String>,
  val temperature: Double?,
)

/** One message of the conversation in the shape the engine needs. */
internal sealed interface ChatMessage {
  data class User(val parts: List<String>) : ChatMessage

  /** A system message after the first message; the leading one is [ConversationSetup.system]. */
  data class System(val text: String) : ChatMessage

  data class Assistant(val text: String, val toolCalls: List<ToolCall>) : ChatMessage

  /** A tool's result; [name] comes from the assistant call that [callId] answers, since LiteRT-LM keys results by name. */
  data class ToolResult(val callId: String, val name: String, val text: String) : ChatMessage
}

/** A tool call with its arguments as canonical JSON, or as sent when they aren't a JSON object. */
internal data class ToolCall(val id: String, val name: String, val arguments: String)

/** A request the engine can't serve, answered with HTTP 400 and [message]. */
internal class BadRequestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Parses a Chat Completions body as pi-ai sends it. Throws [BadRequestException] for anything LiteRT-LM can't run. */
internal fun parseChatRequest(body: String): ChatRequest {
  val json = try {
    JSONObject(body)
  } catch (failure: JSONException) {
    throw BadRequestException("Malformed JSON: ${failure.message}", failure)
  }
  val rawMessages = json.optJSONArray("messages") ?: throw BadRequestException("messages is required")
  var system: String? = null
  val messages = mutableListOf<ChatMessage>()
  val toolNames = mutableMapOf<String, String>()
  for (index in 0 until rawMessages.length()) {
    val message = rawMessages.optJSONObject(index) ?: throw BadRequestException("messages[$index] is not an object")
    when (val role = message.optString("role")) {
      "system", "developer" -> {
        val text = textOf(message.opt("content"), index)
        if (index == 0) system = text else messages += ChatMessage.System(text)
      }

      "user" -> messages += ChatMessage.User(partsOf(message.opt("content"), index))

      "assistant" -> {
        val toolCalls = toolCallsOf(message.optJSONArray("tool_calls"), index)
        toolCalls.forEach { toolNames[it.id] = it.name }
        messages += ChatMessage.Assistant(textOf(message.opt("content"), index), toolCalls)
      }

      "tool" -> {
        val callId = message.optString("tool_call_id")
        val name = toolNames[callId] ?: message.optString("name").ifEmpty {
          throw BadRequestException("messages[$index] answers an unknown tool call '$callId'")
        }
        messages += ChatMessage.ToolResult(callId, name, textOf(message.opt("content"), index))
      }

      else -> throw BadRequestException("messages[$index] has an unsupported role '$role'")
    }
  }
  val effort = json.optString("reasoning_effort")
  return ChatRequest(
    setup = ConversationSetup(
      system = system,
      tools = toolsOf(json.optJSONArray("tools")),
      temperature = json.optDouble("temperature").takeUnless { it.isNaN() },
    ),
    messages = messages,
    stream = json.optBoolean("stream"),
    includeUsage = json.optJSONObject("stream_options")?.optBoolean("include_usage") == true,
    maxTokens = positiveInt(json, "max_completion_tokens") ?: positiveInt(json, "max_tokens"),
    thinking = effort != "none",
  )
}

private fun positiveInt(json: JSONObject, key: String): Int? = json.optInt(key, 0).takeIf { it > 0 }

private fun toolsOf(tools: JSONArray?): List<String> {
  if (tools == null) return emptyList()
  return (0 until tools.length()).map { index ->
    val tool = tools.optJSONObject(index)
    val function = tool?.optJSONObject("function")
    if (tool?.optString("type") != "function" || function == null || function.optString("name").isEmpty()) {
      throw BadRequestException("tools[$index] is not a function tool with a name")
    }
    canonicalJson(function)
  }
}

private fun toolCallsOf(calls: JSONArray?, messageIndex: Int): List<ToolCall> {
  if (calls == null) return emptyList()
  return (0 until calls.length()).map { index ->
    val function = calls.optJSONObject(index)?.optJSONObject("function")
      ?: throw BadRequestException("messages[$messageIndex].tool_calls[$index] has no function")
    ToolCall(
      id = calls.getJSONObject(index).optString("id"),
      name = function.optString("name"),
      arguments = canonicalArguments(function.opt("arguments")),
    )
  }
}

/** Arguments arrive as a JSON string; comparing them parsed ignores how the client re-serialized them. */
private fun canonicalArguments(arguments: Any?): String = when (arguments) {
  is JSONObject -> canonicalJson(arguments)

  is String -> try {
    canonicalJson(JSONObject(arguments))
  } catch (_: JSONException) {
    arguments
  }

  else -> "{}"
}

/** A content string, or the text of an array of parts; LiteRT-LM's text models take nothing else. */
private fun textOf(content: Any?, index: Int): String = partsOf(content, index).joinToString("")

private fun partsOf(content: Any?, index: Int): List<String> = when (content) {
  null, JSONObject.NULL -> emptyList()

  is String -> listOf(content)

  is JSONArray -> (0 until content.length()).map { part ->
    val item = content.optJSONObject(part)
    if (item?.optString("type") != "text") {
      throw BadRequestException("messages[$index] has a '${item?.optString("type")}' part; only text is supported")
    }
    item.optString("text")
  }

  else -> throw BadRequestException("messages[$index] has unsupported content")
}

/** [value] as JSON with every object's keys sorted, so two encodings of the same value compare equal as strings. */
internal fun canonicalJson(value: Any?): String = StringBuilder().also { appendCanonical(it, value) }.toString()

private fun appendCanonical(out: StringBuilder, value: Any?) {
  when (value) {
    is JSONObject -> {
      out.append('{')
      value.keys().asSequence().sorted().forEachIndexed { index, key ->
        if (index > 0) out.append(',')
        out.append(JSONObject.quote(key)).append(':')
        appendCanonical(out, value.opt(key))
      }
      out.append('}')
    }

    is JSONArray -> {
      out.append('[')
      for (index in 0 until value.length()) {
        if (index > 0) out.append(',')
        appendCanonical(out, value.opt(index))
      }
      out.append(']')
    }

    is String -> out.append(JSONObject.quote(value))

    null, JSONObject.NULL -> out.append("null")

    is Number -> out.append(JSONObject.numberToString(value))

    else -> out.append(value.toString())
  }
}
