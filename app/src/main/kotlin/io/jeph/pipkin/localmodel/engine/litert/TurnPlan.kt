package io.jeph.pipkin.localmodel.engine.litert

/**
 * Everything the live LiteRT-LM conversation holds in its cache: the setup it was created with and every message it
 * has seen, including the replies it wrote. A request whose history matches it only needs its new tail sent.
 */
internal data class Transcript(val setup: ConversationSetup, val messages: List<ChatMessage>)

/** How to run one request against the live conversation. */
internal sealed interface TurnPlan {
  /** The message LiteRT-LM generates a reply to: one user message, or the results of one turn's tool calls. */
  val input: List<ChatMessage>

  /** The live conversation already holds everything before [input]. */
  data class Continue(override val input: List<ChatMessage>) : TurnPlan

  /** A new conversation, seeded with [history], has to take [input]. */
  data class Rebuild(val history: List<ChatMessage>, override val input: List<ChatMessage>) : TurnPlan
}

/**
 * Plans [request] against [live]. LiteRT-LM appends one message per call and generates a reply to it, so the request
 * must end in a user message or in tool results; only when everything before that is what [live] already holds can
 * the conversation, and the model's cache with it, be reused.
 */
internal fun planTurn(live: Transcript?, request: ChatRequest): TurnPlan {
  val messages = request.messages
  val inputStart = when (messages.lastOrNull()) {
    is ChatMessage.User -> messages.lastIndex
    is ChatMessage.ToolResult -> messages.indexOfLast { it !is ChatMessage.ToolResult } + 1
    else -> throw BadRequestException("The last message must be from the user or a tool")
  }
  val history = messages.subList(0, inputStart)
  val input = messages.subList(inputStart, messages.size)
  val reusable = live != null && live.setup == request.setup && live.messages.size == history.size &&
    live.messages.zip(history).all { (seen, sent) -> sameMessage(seen, sent) }
  return if (reusable) TurnPlan.Continue(input) else TurnPlan.Rebuild(history, input)
}

/**
 * Whether [sent], as the client echoes it back, is the message the conversation saw as [seen]. Clients trim replies
 * and pick their own tool call ids for earlier turns, and neither changes what the model was given.
 */
private fun sameMessage(seen: ChatMessage, sent: ChatMessage): Boolean = when {
  seen is ChatMessage.Assistant && sent is ChatMessage.Assistant ->
    seen.text.trim() == sent.text.trim() &&
      seen.toolCalls.map { it.name to it.arguments } == sent.toolCalls.map { it.name to it.arguments }

  seen is ChatMessage.ToolResult && sent is ChatMessage.ToolResult -> seen.name == sent.name && seen.text == sent.text

  else -> seen == sent
}
