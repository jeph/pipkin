package io.jeph.pipkin.agent

import io.jeph.pipkin.agent.TimelineItem.AssistantText
import io.jeph.pipkin.agent.TimelineItem.ExitBanner
import io.jeph.pipkin.agent.TimelineItem.Notice
import io.jeph.pipkin.agent.TimelineItem.Thinking
import io.jeph.pipkin.agent.TimelineItem.ToolCall
import io.jeph.pipkin.agent.TimelineItem.TurnFooter
import io.jeph.pipkin.agent.TimelineItem.User
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Folds Pi RPC session events (`docs/json.md`) into the items the chat screen renders. Pure state without I/O;
 * every entry point is synchronized, so callers may use any thread.
 *
 * Message items are keyed `m<seq>` (user, bash) or `m<seq>.<contentIndex>` (assistant blocks), so a block streamed
 * through `message_update` keeps its key when `message_end` rebuilds the authoritative message. The open run's
 * [TurnFooter] is held apart and always rendered last.
 */
class ChatTimeline(private val clock: () -> Long = System::currentTimeMillis) {
  private val lock = Any()
  private val mutableState = MutableStateFlow(TimelineState())
  private val effectChannel = Channel<TimelineEffect>(Channel.UNLIMITED)

  val state: StateFlow<TimelineState> = mutableState.asStateFlow()

  /** Single-collector stream of one-shot extension requests; buffered until collected. */
  val effects: Flow<TimelineEffect> = effectChannel.receiveAsFlow()

  private val items = mutableListOf<TimelineItem>()
  private var footer: TurnFooter? = null
  private var live: LiveMessage? = null

  /** Each streaming tool call's arguments so far, by item key. */
  private val streamingArgs = HashMap<String, StreamedArguments>()

  private var messageSeq = 0
  private var itemSeq = 0
  private var retryNoticeKey: String? = null
  private var compactionNoticeKey: String? = null
  private var busy = false

  /** Between `compaction_start` and `compaction_end`; a manual compaction runs outside any agent run. */
  private var compacting = false
  private var queue = emptyList<String>()
  private var queueInFlight = false
  private var pendingUi: ExtensionUiRequest? = null
  private var model: JSONObject? = null
  private var thinkingLevel: String? = null
  private var appliedCount = 0L

  /** How many events [apply] has taken so far; [seed] can compare against it. */
  val appliedEvents: Long get() = synchronized(lock) { appliedCount }

  /** Every user message Pi started, in order. */
  val startedUserMessages = StartedUserMessages()

  /**
   * Replaces the stream with `get_messages` `data.messages`. Pi is idle whenever its history is seeded. With
   * [ifNoEventsSince], it seeds only when no event was applied after [appliedEvents] read that value: the stream is
   * live then and the history may predate it. That check holds [apply]'s lock, so no event lands in between.
   * Returns whether it seeded.
   */
  fun seed(messages: JSONArray, ifNoEventsSince: Long? = null): Boolean {
    synchronized(lock) {
      if (ifNoEventsSince != null && appliedCount != ifNoEventsSince) return false
      items.clear()
      live = null
      // Every message key is cleared above, so numbering restarts: reseeding the same history, as a parked chat's
      // restart does, gives each row the key it had and the list keeps its scroll position and row state.
      messageSeq = 0
      streamingArgs.clear()
      // A seed follows a new connection, a session switch or a re-sync with an idle Pi, so neither a run nor a
      // compaction is still going.
      busy = false
      footer = null
      compacting = false
      queueInFlight = false
      for (index in 0 until messages.length()) {
        val message = messages.optJSONObject(index) ?: continue
        when (message.optString("role")) {
          "user", "assistant", "bashExecution" ->
            items += messageItems(message, nextMessageKey(), settled = true, message.piTimestamp ?: clock())

          "toolResult" -> attachToolResult(message)
        }
      }
      publish()
      return true
    }
  }

  /** Applies one non-response Pi stdout record. Unknown events are ignored. */
  fun apply(event: JSONObject) {
    synchronized(lock) {
      appliedCount += 1
      dispatch(event)
      publish()
    }
  }

  fun setModel(model: JSONObject?) {
    synchronized(lock) {
      this.model = model
      publish()
    }
  }

  fun setThinkingLevel(level: String?) {
    synchronized(lock) {
      thinkingLevel = level
      publish()
    }
  }

  /** Pi exited: ends any open run and appends an [ExitBanner]. */
  fun showExit(code: Int?, stderr: String) {
    synchronized(lock) {
      finishRun()
      compacting = false
      queueInFlight = false
      pendingUi = null
      items += ExitBanner("x${itemSeq++}", code, stderr)
      publish()
    }
  }

  fun clearPendingUi() {
    synchronized(lock) {
      pendingUi = null
      publish()
    }
  }

  /**
   * The raw JSON of tool call [key]'s arguments so far, or null once they finished streaming. The timeline item
   * only carries what the chat row shows, so a view of the whole arguments parses this text itself.
   */
  fun streamingArguments(key: String): String? = synchronized(lock) { streamingArgs[key]?.text?.toString() }

  private fun dispatch(event: JSONObject) {
    when (event.optString("type")) {
      "agent_start" -> startRun()

      "agent_settled" -> finishRun()

      // Pi takes the steers for the next turn right after `turn_end` and delivers them before its reply starts.
      "turn_end" -> queueInFlight = true

      "agent_end" -> queueInFlight = false

      "message_start" -> event.optJSONObject("message")?.let(::messageStart)

      "message_update" -> event.optJSONObject("assistantMessageEvent")?.let(::messageUpdate)

      "message_end" -> event.optJSONObject("message")?.let(::messageEnd)

      "tool_execution_start", "tool_execution_update", "tool_execution_end" -> toolExecution(event)

      "queue_update" -> queue = event.optJSONArray("steering").strings() + event.optJSONArray("followUp").strings()

      "auto_retry_start" -> retryNoticeKey = showNotice(retryNoticeKey, "Retrying…")

      "auto_retry_end" -> clearRetryNotice()

      "compaction_start" -> {
        compacting = true
        compactionNoticeKey = showNotice(compactionNoticeKey, "Compacting context…")
      }

      "compaction_end" -> compactionEnd(event)

      "thinking_level_changed" -> thinkingLevel = event.optStringOrNull("level")

      "extension_ui_request" -> extensionUi(event)

      // Pi catches a failing extension command and still answers its prompt as handled, so this is the only report.
      "extension_error" -> items += TimelineItem.Error(
        "e${itemSeq++}",
        "Extension \"${event.optString("extensionPath")}\" error: ${event.optString("error")}",
      )
    }
  }

  private fun startRun() {
    busy = true
    if (footer == null) footer = TurnFooter("t${itemSeq++}", clock(), null)
  }

  private fun finishRun() {
    busy = false
    live = null
    streamingArgs.clear()
    items.replaceAll(::settle)
    footer?.let { items += it.copy(durationMillis = clock() - it.startedAt) }
    footer = null
    clearRetryNotice()
  }

  private fun clearRetryNotice() {
    retryNoticeKey?.let { put(it, null) }
    retryNoticeKey = null
  }

  private fun messageStart(message: JSONObject) {
    val timestamp = message.piTimestamp ?: clock()
    when (message.optString("role")) {
      "user" -> {
        val key = nextMessageKey()
        live = LiveMessage(key, "user", timestamp)
        items += messageItems(message, key, settled = false, timestamp)
        startedUserMessages.add(message.optJSONArray("content")?.text() ?: message.optString("content"))
      }

      "assistant" -> {
        live = LiveMessage(nextMessageKey(), "assistant", timestamp)
        queueInFlight = false
      }
    }
  }

  private fun messageUpdate(update: JSONObject) {
    val index = update.optInt("contentIndex", -1)
    if (index < 0) return
    val current = live?.takeIf { it.role == "assistant" } ?: LiveMessage(nextMessageKey(), "assistant", clock())
    live = current
    val key = "${current.key}.$index"
    val newText = { AssistantText(key, "", streaming = true, current.timestamp) }
    val newThinking = { Thinking(key, "", streaming = true) }
    val content = update.optStringOrNull("content")
    when (update.optString("type")) {
      "text_start" -> upsert(key, newText) { it }

      "text_delta" -> upsert(key, newText) { it.copy(markdown = it.markdown + update.optString("delta")) }

      "text_end" -> upsert(key, newText) { it.copy(markdown = content ?: it.markdown, streaming = false) }

      "thinking_start" -> upsert(key, newThinking) { it }

      "thinking_delta" -> upsert(key, newThinking) { it.copy(text = it.text + update.optString("delta")) }

      "thinking_end" -> upsert(key, newThinking) { it.copy(text = content ?: it.text, streaming = false) }

      "toolcall_start" -> {
        // Registered before the first delta, so streamingArguments is only null once the arguments ended.
        streamingArgs.getOrPut(key, ::StreamedArguments)
        upsert(key, { pendingTool(key, update.optString("id"), update.optString("toolName")) }) {
          it.copy(argsStreaming = true)
        }
      }

      "toolcall_delta" -> {
        val streamed = streamingArgs.getOrPut(key, ::StreamedArguments)
        streamed.text.append(update.optString("delta"))
        val position = items.indexOfLast { it.key == key }
        (items.getOrNull(position) as? ToolCall)?.let { tool ->
          streamed.parseForRow(rowArgument(tool.toolName))?.let { items[position] = tool.copy(args = it) }
        }
      }

      "toolcall_end" -> {
        streamingArgs.remove(key)
        val call = update.optJSONObject("toolCall") ?: return
        upsert(key, { pendingTool(key, call.optString("id"), call.optString("name")) }) {
          it.copy(args = call.optJSONObject("arguments"), argsStreaming = false)
        }
      }
    }
  }

  private fun messageEnd(message: JSONObject) {
    when (val role = message.optString("role")) {
      "user", "assistant", "bashExecution" -> {
        val started = live?.takeIf { it.role == role }
        val prefix = started?.key ?: nextMessageKey()
        live = null
        // An aborted message ends without toolcall_end for the call it was streaming.
        if (role == "assistant") streamingArgs.clear()
        val timestamp = message.piTimestamp ?: started?.timestamp ?: clock()
        replaceMessage(prefix, messageItems(message, prefix, settled = role == "bashExecution", timestamp))
      }

      "toolResult" -> attachToolResult(message)
    }
  }

  /** Swaps every item of message [prefix] for [rebuilt], keeping tool progress already reported by execution events. */
  private fun replaceMessage(prefix: String, rebuilt: List<TimelineItem>) {
    val merged =
      rebuilt.map { item ->
        val old = (item as? ToolCall)?.let { findTool(it.id) }?.let { items[it] as ToolCall }
        if (item is ToolCall && old != null) {
          item.copy(output = old.output, isError = old.isError, status = old.status)
        } else {
          item
        }
      }
    val first = items.indexOfFirst { it.belongsTo(prefix) }
    items.removeAll { it.belongsTo(prefix) }
    items.addAll(if (first < 0) items.size else first, merged)
  }

  private fun toolExecution(event: JSONObject) {
    val id = event.optString("toolCallId")
    val args = event.optJSONObject("args")
    val index =
      findTool(id) ?: items.size.also {
        items += ToolCall("tool:$id", id, event.optString("toolName"), args, null, false, ToolStatus.Pending)
      }
    val tool = items[index] as ToolCall
    items[index] =
      when (event.optString("type")) {
        "tool_execution_start" -> tool.copy(args = tool.args ?: args, status = ToolStatus.Running)

        "tool_execution_update" ->
          tool.copy(
            output = event.optJSONObject("partialResult")?.optJSONArray("content").text(),
            status = ToolStatus.Running,
          )

        else ->
          tool.copy(
            output = event.optJSONObject("result")?.optJSONArray("content").text(),
            isError = event.optBoolean("isError"),
            status = ToolStatus.Done,
          )
      }
  }

  private fun attachToolResult(message: JSONObject) {
    val index = findTool(message.optString("toolCallId")) ?: return
    items[index] =
      (items[index] as ToolCall).copy(
        output = message.optJSONArray("content").text(),
        isError = message.optBoolean("isError"),
        status = ToolStatus.Done,
      )
  }

  private fun compactionEnd(event: JSONObject) {
    compacting = false
    val key = compactionNoticeKey ?: "n${itemSeq++}"
    compactionNoticeKey = null
    val replacement =
      when {
        event.optJSONObject("result") != null -> Notice(key, "Context compacted")
        event.optBoolean("aborted") -> null
        else -> TimelineItem.Error(key, event.optStringOrNull("errorMessage") ?: "Compaction failed")
      }
    put(key, replacement)
  }

  private fun extensionUi(event: JSONObject) {
    when (val method = event.optString("method")) {
      "select", "confirm", "input", "editor" ->
        pendingUi =
          ExtensionUiRequest(
            id = event.optString("id"),
            method = method,
            title = event.optStringOrNull("title"),
            message = event.optStringOrNull("message"),
            options = event.optJSONArray("options").strings(),
            placeholder = event.optStringOrNull("placeholder"),
            prefill = event.optStringOrNull("prefill"),
          )

      "notify" ->
        effectChannel.trySend(
          TimelineEffect.Notify(event.optString("message"), event.optStringOrNull("notifyType")),
        )

      "set_editor_text" -> effectChannel.trySend(TimelineEffect.SetEditorText(event.optString("text")))
    }
  }

  /** Shows [text] in the notice [key], creating a new notice when [key] is null. Returns the notice's key. */
  private fun showNotice(key: String?, text: String): String {
    val noticeKey = key ?: "n${itemSeq++}"
    put(noticeKey, Notice(noticeKey, text))
    return noticeKey
  }

  /** Replaces the item [key] with [item], appending it when absent; a null [item] removes [key]. */
  private fun put(key: String, item: TimelineItem?) {
    val index = items.indexOfFirst { it.key == key }
    when {
      item == null -> if (index >= 0) items.removeAt(index)
      index >= 0 -> items[index] = item
      else -> items += item
    }
  }

  private inline fun <reified T : TimelineItem> upsert(key: String, create: () -> T, change: (T) -> T) {
    val index = items.indexOfLast { it.key == key }
    if (index < 0) {
      items += change(create())
    } else {
      (items[index] as? T)?.let { items[index] = change(it) }
    }
  }

  private fun findTool(id: String): Int? = items.indexOfLast { it is ToolCall && it.id == id }.takeIf { it >= 0 }

  private fun nextMessageKey(): String = "m${messageSeq++}"

  private fun publish() {
    val snapshot = footer?.let { items + it } ?: items.toList()
    // A manual compaction runs outside any agent run, but it is work Stop can end and keep-alive must cover.
    mutableState.value =
      TimelineState(snapshot, busy || compacting, queue, pendingUi, model, thinkingLevel, queueInFlight)
  }

  private data class LiveMessage(val key: String, val role: String, val timestamp: Long)

  private class StreamedArguments {
    val text = StringBuilder()
    private var rowArgumentFinal = false

    /**
     * The arguments so far for a chat row showing [rowArgument], or null once that argument is final. Parsing
     * costs as much as the text is long, and nothing on screen needs more than the row until a sheet opens.
     */
    fun parseForRow(rowArgument: String): JSONObject? {
      if (rowArgumentFinal) return null
      val parsed = readPartialJsonObject(text.toString()) ?: return null
      rowArgumentFinal = parsed.value.has(rowArgument) && parsed.lastKey != rowArgument
      return parsed.value
    }
  }
}

/**
 * Counts the user messages Pi started and keeps the texts of the latest ones, so a caller can tell which messages
 * arrived after a [mark]. Safe to use from any thread.
 */
class StartedUserMessages {
  private var count = 0L
  private val recentTexts = ArrayDeque<String>()

  /** The messages started so far, for [textsSince]. */
  @Synchronized
  fun mark(): Long = count

  /** The texts of the messages started after [mark] was taken, oldest first. */
  @Synchronized
  fun textsSince(mark: Long): List<String> =
    recentTexts.takeLast((count - mark).coerceIn(0L, recentTexts.size.toLong()).toInt())

  @Synchronized
  internal fun add(text: String) {
    count += 1
    recentTexts.addLast(text)
    if (recentTexts.size > RECENT_TEXTS) recentTexts.removeFirst()
  }

  private companion object {
    /** More than Pi can queue and deliver while a caller holds a mark. */
    const val RECENT_TEXTS = 64
  }
}

/**
 * Rebuilds the items of one `user`, `assistant` or `bashExecution` message sent at [timestamp]; [settled] marks
 * tool calls finished.
 */
private fun messageItems(message: JSONObject, prefix: String, settled: Boolean, timestamp: Long): List<TimelineItem> =
  when (message.optString("role")) {
    "user" -> listOf(userItem(message, prefix, timestamp))

    "assistant" -> assistantItems(message, prefix, settled, timestamp)

    "bashExecution" -> {
      val exitCode = if (message.isNull("exitCode")) 0 else message.optInt("exitCode")
      val args = JSONObject().put("command", message.optString("command"))
      listOf(ToolCall(prefix, prefix, "bash", args, message.optString("output"), exitCode != 0, ToolStatus.Done))
    }

    else -> emptyList()
  }

private fun userItem(message: JSONObject, key: String, timestamp: Long): User {
  val blocks = message.optJSONArray("content")
    ?: return User(key, typedText(message.optString("content")), emptyList(), timestamp)
  val images =
    (0 until blocks.length()).mapNotNull { index ->
      blocks.optJSONObject(index)?.takeIf { it.optString("type") == "image" }?.let {
        ImagePart(it.optString("data"), it.optString("mimeType"))
      }
    }
  return User(key, typedText(blocks.text()), images, timestamp)
}

/** Pi's expansion of `/skill:name args` (its `parseSkillBlock` format): the skill's file wrapped in a tag, then the args. */
private val SKILL_BLOCK = Regex("""<skill name="([^"]+)" location="[^"]+">\n[\s\S]*?\n</skill>(?:\n\n([\s\S]+))?""")

/** A user message as typed: an expanded skill shows as its `/skill:name args` command, like Pi's terminal UI. */
internal fun typedText(text: String): String {
  val match = SKILL_BLOCK.matchEntire(text) ?: return text
  val args = match.groupValues[2].trim()
  return "/skill:${match.groupValues[1]}" + if (args.isEmpty()) "" else " $args"
}

private fun assistantItems(message: JSONObject, prefix: String, settled: Boolean, timestamp: Long): List<TimelineItem> =
  buildList {
    val blocks = message.optJSONArray("content") ?: JSONArray()
    for (index in 0 until blocks.length()) {
      val block = blocks.optJSONObject(index) ?: continue
      val key = "$prefix.$index"
      when (block.optString("type")) {
        "text" -> block.optString("text").takeIf { it.isNotBlank() }?.let {
          add(AssistantText(key, it, streaming = false, timestamp))
        }

        "thinking" -> add(Thinking(key, block.optString("thinking"), streaming = false))

        "toolCall" -> {
          val status = if (settled) ToolStatus.Done else ToolStatus.Pending
          val args = block.optJSONObject("arguments")
          add(ToolCall(key, block.optString("id"), block.optString("name"), args, null, false, status))
        }
      }
    }
    if (message.optString("stopReason") == "error") {
      add(TimelineItem.Error("$prefix.error", message.optStringOrNull("errorMessage") ?: "Request failed"))
    }
  }

private fun pendingTool(key: String, id: String, toolName: String) =
  ToolCall(key, id, toolName, null, null, false, ToolStatus.Pending)

/** The argument a tool call's chat row shows (see `toolPresentation`). */
private fun rowArgument(toolName: String) = if (toolName == "bash") "command" else "path"

/** Ends streaming on a run that settled: nothing is still typing or executing. */
private fun settle(item: TimelineItem): TimelineItem = when {
  item is AssistantText && item.streaming -> item.copy(streaming = false)
  item is Thinking && item.streaming -> item.copy(streaming = false)
  item is ToolCall && item.status != ToolStatus.Done -> item.copy(status = ToolStatus.Done, argsStreaming = false)
  else -> item
}

private fun TimelineItem.belongsTo(prefix: String) = key == prefix || key.startsWith("$prefix.")

/** Text blocks of a content array, joined by newlines; image blocks are skipped. */
private fun JSONArray?.text(): String {
  if (this == null) return ""
  return (0 until length())
    .mapNotNull { optJSONObject(it)?.takeIf { block -> block.optString("type") == "text" }?.optString("text") }
    .joinToString("\n")
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }

/** Android's `optString` turns JSON null into "null"; this keeps it null. */
private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)

/** The message's `timestamp`, or null when Pi sent none. */
private val JSONObject.piTimestamp: Long? get() = optLong("timestamp").takeIf { it > 0 }
