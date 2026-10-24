package io.jeph.pipkin.agent

import io.jeph.pipkin.host.objects
import org.json.JSONObject

/** Where a subagent run stands, as the `pipkin-subagents` extension reports it. */
internal enum class SubagentStatus {
  /** Waiting for one of the chat's parallel slots. */
  Queued,
  Running,
  Done,
  Failed,

  /** Stopped by the user, or by the parent's run ending. */
  Stopped,
  ;

  val active: Boolean get() = this == Queued || this == Running

  companion object {
    fun from(value: String): SubagentStatus? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
  }
}

/**
 * One subagent the chat's Pi runs or ran since the user's last message, from the `runs` list the `pipkin-subagents`
 * extension sends on Pipkin's channel. [id] is the run's own id, [toolCallId] the parent's `subagent` tool call that
 * started it. [agent] is the agent definition's name, [description] the few words the model gave the task. Times are
 * epoch milliseconds. [messages] counts the messages in the subagent's transcript, so a screen showing it knows when
 * to read it again. [inFlight] counts the subagent's tool calls handed to the parent that haven't come back, which
 * [askingSubagent] uses to tell whose question the chat's card shows.
 */
internal data class SubagentRun(
  val id: String,
  val toolCallId: String,
  val agent: String,
  val description: String,
  val status: SubagentStatus,
  val startedAt: Long?,
  val endedAt: Long?,
  val turn: Int,
  val tokens: Long,
  val lastActivity: String?,
  val error: String?,
  val sessionFile: String?,
  val messages: Int,
  val inFlight: Int,
) {
  companion object {
    /** The runs in a `runs` message's data; a run missing its ids or status is left out. */
    fun listFrom(data: JSONObject): List<SubagentRun> = data.optJSONArray("runs").objects().mapNotNull(::from)

    private fun from(json: JSONObject): SubagentRun? {
      val id = json.optString("id").ifEmpty { return null }
      val toolCallId = json.optString("toolCallId").ifEmpty { return null }
      val status = SubagentStatus.from(json.optString("status")) ?: return null
      return SubagentRun(
        id = id,
        toolCallId = toolCallId,
        agent = json.optString("agent"),
        description = json.optString("description"),
        status = status,
        startedAt = json.optLong("startedAt").takeIf { it > 0 },
        endedAt = json.optLong("endedAt").takeIf { it > 0 },
        turn = json.optInt("turn"),
        tokens = json.optLong("tokens"),
        lastActivity = json.textOrNull("lastActivity"),
        error = json.textOrNull("error"),
        sessionFile = json.textOrNull("sessionFile"),
        messages = json.optInt("messages"),
        inFlight = json.optInt("inFlight"),
      )
    }

    private fun JSONObject.textOrNull(name: String): String? =
      if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
  }
}

/** Whose question the chat's front dialog is, as far as Pipkin can tell; see [askingSubagent]. */
internal sealed interface SubagentAsker {
  /** The question can't be told apart from the parent's own, so no subagent is named. */
  data object Unknown : SubagentAsker

  /** Only subagents could have asked, but more than one: "From a subagent". */
  data object Several : SubagentAsker

  /** Only [run] could have asked. */
  data class One(val run: SubagentRun) : SubagentAsker
}

/**
 * Who asked the dialog the chat's card shows, from the chat's [runs] and its [timeline]. Pi's `extension_ui_request`
 * doesn't say which tool call raised it, and Pi emits `tool_execution_start` before a call's permission checks run, so
 * a subagent is named only when nothing else could have asked: exactly one run has tool calls in flight and the
 * parent has none of its own running. A `subagent` call counts as the parent's own until its run appears, since until
 * then it may be the call a permission extension asks about. Never a guess between subagents.
 */
internal fun askingSubagent(timeline: TimelineState, runs: List<SubagentRun>): SubagentAsker {
  if (timeline.pendingUi == null) return SubagentAsker.Unknown
  val started = runs.mapTo(HashSet()) { it.toolCallId }
  val parentBusy = timeline.items.any { item ->
    item is TimelineItem.ToolCall &&
      item.status == ToolStatus.Running &&
      !(item.toolName == SUBAGENT_TOOL && item.id in started)
  }
  if (parentBusy) return SubagentAsker.Unknown
  val asking = runs.filter { it.inFlight > 0 }
  return when (asking.size) {
    0 -> SubagentAsker.Unknown
    1 -> SubagentAsker.One(asking.single())
    else -> SubagentAsker.Several
  }
}

/** The tool the `pipkin-subagents` extension registers. */
internal const val SUBAGENT_TOOL = "subagent"

/** The Pipkin channel topic the `pipkin-subagents` extension and the app talk on. */
internal const val SUBAGENTS_TOPIC = "subagents"

/**
 * What a finished `subagent` tool call's result `details` say about its run: the run's [id], [agent] and
 * [description], the transcript at [sessionFile] (null for a subagent that ran in memory), how it ended, and the
 * [tokens] it used. They outlive the run's place in [SubagentRun]'s list, so the tool row leads back to it.
 */
internal data class SubagentResult(
  val id: String?,
  val agent: String?,
  val description: String?,
  val sessionFile: String?,
  val status: SubagentStatus?,
  val tokens: Long?,
) {
  companion object {
    /** The result of a call whose `details` are [details]; null when they hold none of a subagent's. */
    fun from(details: JSONObject?): SubagentResult? {
      if (details == null) return null
      val result = SubagentResult(
        id = details.textOrNull("id"),
        agent = details.textOrNull("agent"),
        description = details.textOrNull("description"),
        sessionFile = details.textOrNull("sessionFile"),
        status = details.textOrNull("status")?.let(SubagentStatus::from),
        tokens = details.optJSONObject("usage")?.optLong("totalTokens")?.takeIf { it > 0 },
      )
      return result.takeUnless { it == EMPTY }
    }

    private val EMPTY = SubagentResult(null, null, null, null, null, null)
  }
}

private fun JSONObject.textOrNull(name: String): String? =
  if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
