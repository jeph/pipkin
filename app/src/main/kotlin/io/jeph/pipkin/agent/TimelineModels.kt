package io.jeph.pipkin.agent

import org.json.JSONObject

/** Everything the chat screen renders for one Pi process. [model] is Pi's model JSON (`provider`, `id`, `input`). */
data class TimelineState(
  val items: List<TimelineItem> = emptyList(),
  val busy: Boolean = false,
  val queue: List<String> = emptyList(),
  /** The first of the dialogs Pi waits on; the next shows once this one is answered or expires. */
  val pendingUi: ExtensionUiRequest? = null,
  /** The ids of every dialog Pi waits on, oldest first, [pendingUi]'s among them. */
  val pendingUiIds: List<String> = emptyList(),
  /**
   * [pendingUi]'s place among the dialogs asked since none were waiting, counting from 1, and how many of those
   * were shown or still wait, so "2 of 3" grows as more come in while the user answers.
   */
  val pendingUiNumber: Int = 0,
  val pendingUiCount: Int = 0,
  val model: JSONObject? = null,
  val thinkingLevel: String? = null,
  /**
   * Pi may already have taken [queue]'s messages for its next turn: from `turn_end` until that turn's reply starts
   * or the run ends. `clear_queue` still returns them meanwhile, yet Pi delivers them anyway.
   */
  val queueInFlight: Boolean = false,
) {
  /**
   * [items] as the stream shows them, with each run of two or more steps folded into [TimelineItem.Steps]. Built once,
   * on first read; read it off the main thread first, as the screen's timeline does, since it walks every item.
   */
  val stream: StepStream by lazy { StepStream.of(items) }

  /** This state with its [stream] built, for a screen to read without walking the items itself. */
  fun withStream(): TimelineState = apply { stream }
}

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
   * `bash`) is final; [ChatTimeline.streamingArguments] has the raw text that has arrived so far. A call [byUser] is
   * a `!command` the user ran, not a step of the agent's. [figure] is what its row notes about the result. [details]
   * are the result's `details`, kept only for a `subagent` call ([SUBAGENT_TOOL]), whose row leads to the run's
   * transcript through them once the run is gone; see [SubagentResult].
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
    val byUser: Boolean = false,
    val figure: StepFigure? = null,
    val details: JSONObject? = null,
  ) : TimelineItem

  data class Error(override val key: String, val cause: ErrorCause) : TimelineItem

  /**
   * A message an extension added with `pi.sendMessage` and `display` set: Pi sends its [markdown] and [images] to the
   * model as user content, and shows them apart from the conversation under the extension's [customType].
   */
  data class ExtensionMessage(
    override val key: String,
    val customType: String,
    val markdown: String,
    val images: List<ImagePart>,
  ) : TimelineItem

  /**
   * A message an extension showed with `ctx.ui.notify`, or a notice of Pipkin's own ([appNotice]) shown the same way.
   * Pi keeps no record of either, so Pipkin saves it as a [SavedNotice] and puts it back whenever the chat reloads its
   * history.
   */
  data class ExtensionNotice(
    override val key: String,
    val level: NoticeLevel,
    val message: String,
    val appNotice: AppNotice? = null,
  ) : TimelineItem

  data class Notice(override val key: String, val kind: NoticeKind) : TimelineItem

  /**
   * Closes a run that started at [startedAt]; [durationMillis] is null while Pi is still working. A run the user
   * [stopped] ended with Stop.
   */
  data class TurnFooter(
    override val key: String,
    val startedAt: Long,
    val durationMillis: Long?,
    val stopped: Boolean = false,
  ) : TimelineItem

  data class ExitBanner(override val key: String, val code: Int?, val stderr: String) : TimelineItem

  /**
   * Two or more [steps] in a row, which the stream folds into one line: thinking blocks and the agent's tool calls.
   * Keyed by its first step, so the key holds as steps arrive. [current] is the step the folded line shows while the
   * run [outcome] says is still going.
   */
  data class Steps(
    override val key: String,
    val steps: List<TimelineItem>,
    val outcome: StepsOutcome,
    val current: TimelineItem,
  ) : TimelineItem
}

/** What a [TimelineItem.Notice] says about Pi's own work on the chat. */
enum class NoticeKind {
  Retrying,
  Compacting,
  Compacted,
}

/** A notice of Pipkin's own among a session's saved notices; it's shown in the app's language, whenever that changed. */
enum class AppNotice {
  /** The connection to Termux dropped without word of Pi exiting. */
  ConnectionLost,
}

/** What failed in a [TimelineItem.Error]. The messages are Pi's own words, which Pipkin shows as they are. */
sealed interface ErrorCause {
  /** A request to the model failed; [message] is null when Pi gave no reason. */
  data class Request(val message: String?) : ErrorCause

  /** Compacting the context failed; [message] is null when Pi gave no reason. */
  data class Compaction(val message: String?) : ErrorCause

  /** An extension's command threw [error]; [path] is how Pi names the extension. */
  data class Extension(val path: String, val error: String) : ErrorCause
}

/** A base64-encoded image attached to a user message. */
data class ImagePart(val data: String, val mimeType: String)

/** An [TimelineItem.ExtensionNotice]'s `notifyType`; Pi's terminal UI shows any other type as [Info]. */
enum class NoticeLevel {
  Info,
  Warning,
  Error,
  ;

  companion object {
    fun of(notifyType: String?): NoticeLevel = when (notifyType) {
      "warning" -> Warning
      "error" -> Error
      else -> Info
    }
  }
}

/**
 * An extension notice as saved with its session, placed back after the message whose Pi `timestamp` is [after], or
 * first when it came before any message or that message is no longer in the history (a compaction drops the oldest).
 * One that came [afterRun], once the run that message ended had finished, follows that run's footer. A notice of
 * Pipkin's own has its [appNotice] and no [message].
 */
data class SavedNotice(
  val level: NoticeLevel,
  val message: String,
  val after: Long?,
  val afterRun: Boolean,
  val appNotice: AppNotice? = null,
)

enum class ToolStatus { Pending, Running, Done }

/**
 * A blocking `extension_ui_request` dialog (`select`, `confirm`, `input` or `editor`) awaiting an answer. [expiresAt]
 * is when Pi stops waiting and answers with its default ([System.currentTimeMillis]); null when it waits for good.
 */
data class ExtensionUiRequest(
  val id: String,
  val method: String,
  val title: String?,
  val message: String?,
  val options: List<String>,
  val placeholder: String?,
  val prefill: String?,
  val expiresAt: Long? = null,
)

/**
 * How long Pi waits on dialog `extension_ui_request` [event] before answering it with its default, or null when it waits
 * for the answer. Pi treats a `timeout` of 0 as none and answers a negative one at once.
 */
internal fun dialogTimeoutMillis(event: JSONObject): Long? =
  (event.opt("timeout") as? Number)?.toLong()?.takeIf { it != 0L }?.coerceAtLeast(0)

/** One-shot UI actions requested by Pi extensions. */
sealed interface TimelineEffect {
  data class SetEditorText(val text: String) : TimelineEffect
}
