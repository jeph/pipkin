package io.jeph.pipkin.agent

import org.json.JSONObject

/** Everything the chat screen renders for one Pi process. [model] is Pi's model JSON (`provider`, `id`, `input`). */
data class TimelineState(
  val items: List<TimelineItem> = emptyList(),
  val busy: Boolean = false,
  val queue: List<String> = emptyList(),
  val pendingUi: ExtensionUiRequest? = null,
  val model: JSONObject? = null,
  val thinkingLevel: String? = null,
)

/** One row of the chat stream. [key] stays the same while a streamed block is rebuilt from `message_end`. */
sealed interface TimelineItem {
  val key: String

  data class User(override val key: String, val text: String, val images: List<ImagePart>, val timestamp: Long) :
    TimelineItem

  data class AssistantText(override val key: String, val markdown: String, val streaming: Boolean) : TimelineItem

  data class Thinking(override val key: String, val text: String, val streaming: Boolean) : TimelineItem

  data class ToolCall(
    override val key: String,
    val id: String,
    val toolName: String,
    val args: JSONObject?,
    val output: String?,
    val isError: Boolean,
    val status: ToolStatus,
  ) : TimelineItem

  data class Error(override val key: String, val message: String) : TimelineItem

  data class Notice(override val key: String, val text: String) : TimelineItem

  /** Closes a run that started at [startedAt]; [durationMillis] is null while Pi is still working. */
  data class TurnFooter(override val key: String, val startedAt: Long, val durationMillis: Long?) : TimelineItem

  data class ExitBanner(override val key: String, val code: Int?, val stderr: String) : TimelineItem
}

/** A base64-encoded image attached to a user message. */
data class ImagePart(val data: String, val mimeType: String)

enum class ToolStatus { Pending, Running, Done }

/** A blocking `extension_ui_request` dialog (`select`, `confirm`, `input` or `editor`) awaiting an answer. */
data class ExtensionUiRequest(
  val id: String,
  val method: String,
  val title: String?,
  val message: String?,
  val options: List<String>,
  val placeholder: String?,
  val prefill: String?,
)

/** One-shot UI actions requested by Pi extensions. */
sealed interface TimelineEffect {
  data class Notify(val message: String, val notifyType: String?) : TimelineEffect

  data class SetEditorText(val text: String) : TimelineEffect
}
