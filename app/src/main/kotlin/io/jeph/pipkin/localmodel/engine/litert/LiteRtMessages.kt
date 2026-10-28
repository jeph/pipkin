package io.jeph.pipkin.localmodel.engine.litert

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall as LiteRtToolCall
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The seed for a new conversation. Each tool result stays its own message here, the way the client sent it, so the
 * model's chat template renders them as it was trained to.
 */
internal fun historyMessages(history: List<ChatMessage>): List<Message> = history.map { message ->
  when (message) {
    is ChatMessage.ToolResult -> toolMessage(listOf(message))
    else -> liteRtMessage(message)
  }
}

/**
 * The message one turn sends. LiteRT-LM takes a single message per call, so the results of several parallel tool
 * calls go in one tool message with a response each, as LiteRT-LM's own automatic tool calling sends them.
 */
internal fun inputMessage(input: List<ChatMessage>): Message {
  val results = input.filterIsInstance<ChatMessage.ToolResult>()
  if (results.isNotEmpty()) return toolMessage(results)
  return liteRtMessage(input.single())
}

private fun liteRtMessage(message: ChatMessage): Message = when (message) {
  is ChatMessage.User -> Message.user(Contents.of(message.parts.map { Content.Text(it) }))

  is ChatMessage.System -> Message.system(message.text)

  is ChatMessage.Assistant -> {
    val calls = message.toolCalls.map { LiteRtToolCall(it.name, argumentsMap(it.arguments)) }
    if (message.text.isEmpty()) Message.model(toolCalls = calls) else Message.model(Contents.of(message.text), calls)
  }

  is ChatMessage.ToolResult -> toolMessage(listOf(message))
}

private fun toolMessage(results: List<ChatMessage.ToolResult>): Message =
  Message.tool(Contents.of(results.map { Content.ToolResponse(it.name, it.text) }))

/** A tool definition for LiteRT-LM, which renders it into the model's prompt; Pi runs the tools, never this process. */
internal class FunctionTool(private val function: String) : OpenApiTool {
  override fun getToolDescriptionJsonString(): String = function

  override fun execute(paramsJsonString: String): String =
    throw UnsupportedOperationException("Tools run in Pi; automatic tool calling is off")
}

/** LiteRT-LM's tool call arguments as JSON, the string an OpenAI tool call carries. */
internal fun argumentsJson(arguments: Map<String, Any?>): String = canonicalJson(toJson(arguments))

/** [arguments] as the maps, lists and primitives LiteRT-LM serializes; anything but an object becomes no arguments. */
internal fun argumentsMap(arguments: String): Map<String, Any?> {
  val json = try {
    JSONObject(arguments)
  } catch (_: JSONException) {
    return emptyMap()
  }
  return json.keys().asSequence().associateWith { fromJson(json.opt(it)) }
}

private fun fromJson(value: Any?): Any? = when (value) {
  is JSONObject -> value.keys().asSequence().associateWith { fromJson(value.opt(it)) }
  is JSONArray -> (0 until value.length()).map { fromJson(value.opt(it)) }
  JSONObject.NULL -> null
  else -> value
}

private fun toJson(value: Any?): Any? = when (value) {
  is Map<*, *> -> JSONObject().also { json -> value.forEach { (key, item) -> json.put(key.toString(), toJson(item)) } }
  is List<*> -> JSONArray().also { json -> value.forEach { json.put(toJson(it)) } }
  null -> JSONObject.NULL
  else -> value
}
