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
  /**
   * Pi may already have taken [queue]'s messages for its next turn: from `turn_end` until that turn's reply starts
   * or the run ends. `clear_queue` still returns them meanwhile, yet Pi delivers them anyway.
   */
  val queueInFlight: Boolean = false,
)

/**
 * The shortest time between two screen updates of streamed content. Every update re-parses and lays out the
 * whole streamed block, so the screen takes about ten a second rather than one per delta.
 */
internal const val STREAM_UPDATE_MILLIS = 100L

/**
 * One row of the chat stream. [key] stays the same while a streamed block is rebuilt from `message_end`. User
 * messages and text blocks carry their message's `timestamp`: when Pi created it, or when this process first saw it
 * if Pi gave none.
 */
sealed interface TimelineItem {
  val key: String

  data class User(override val key: String, val text: String, val images: List<ImagePart>, val timestamp: Long) :
    TimelineItem

  data class AssistantText(
    override val key: String,
    val markdown: String,
    val streaming: Boolean,
    val timestamp: Long,
  ) : TimelineItem

  data class Thinking(override val key: String, val text: String, val streaming: Boolean) : TimelineItem

  /**
   * While [argsStreaming], [args] is only parsed until the argument the chat row shows (`path`, or `command` for
   * `bash`) is final; [ChatTimeline.streamingArguments] has the raw text that has arrived so far.
   */
  data class ToolCall(
    override val key: String,
    val id: String,
    val toolName: String,
    val args: JSONObject?,
    val output: String?,
    val isError: Boolean,
    val status: ToolStatus,
    val argsStreaming: Boolean = false,
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
