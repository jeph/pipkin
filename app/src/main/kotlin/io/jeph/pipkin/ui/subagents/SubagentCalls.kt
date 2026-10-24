package io.jeph.pipkin.ui.subagents

import androidx.compose.runtime.Immutable
import io.jeph.pipkin.agent.SUBAGENT_TOOL
import io.jeph.pipkin.agent.SubagentAsker
import io.jeph.pipkin.agent.SubagentResult
import io.jeph.pipkin.agent.SubagentRun
import io.jeph.pipkin.agent.SubagentStatus
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.host.objects
import io.jeph.pipkin.ui.chat.stringOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Where a subagent stands, as its tool row, the Subagents sheet and its screen show it. */
internal enum class SubagentPhase {
  /** Waiting for one of the chat's parallel slots, or for its tool call's checks to let it start. */
  Queued,
  Running,

  /** Running, and the question the chat's card shows is certainly its own. */
  Asking,
  Done,
  Failed,

  /** Stopped by the user, or by the parent's run ending. */
  Stopped,

  /** Its tool call never got a result and nothing runs it any more, as when Android stopped Termux mid-run. */
  Interrupted,
  ;

  val active: Boolean get() = this == Queued || this == Running || this == Asking
}

/** Whose `subagent` tool a chat's calls went to, which decides whether they show as subagents. */
internal enum class SubagentTool {
  /**
   * Pipkin subagents', in a chat Pi runs, or not known, as in an archived chat. Either way a finished call is theirs
   * only when its result says so: the user may since have installed another extension's `subagent` tool and reloaded,
   * leaving its calls before then in the chat. One with no result is taken for theirs, since another extension's call
   * cut off can't be told from a subagent interrupted.
   */
  Pipkin,

  /** Another extension's, which Pipkin subagents stands aside for: its calls are ordinary tool calls. */
  Other,
}

/**
 * What a chat knows of its subagents now: the [runs] its extension lists, whose question the chat's card shows
 * ([asker]), whether the chat is [working], which tells a call still waiting to start from one Pi left behind, and
 * whose [tool] its `subagent` calls went to. [cutOff] holds when the runs active as the chat's Pi went away were cut
 * off, by tool call id.
 */
@Immutable
internal class ChatSubagents(
  val runs: List<SubagentRun>,
  val asker: SubagentAsker,
  val working: Boolean,
  val tool: SubagentTool = SubagentTool.Pipkin,
  val cutOff: Map<String, Long> = emptyMap(),
) {
  /** The run [toolCallId] started, while the extension still lists it. */
  fun run(toolCallId: String): SubagentRun? = runs.firstOrNull { it.toolCallId == toolCallId }

  fun phase(call: TimelineItem.ToolCall): SubagentPhase = subagentPhase(call, run(call.id), asker, working)

  fun phase(run: SubagentRun): SubagentPhase = runPhase(run, asker)

  /** The runs queued or running, which the Subagents tab's number counts. */
  val active: Int get() = runs.count { it.status.active }

  /** The runs working, those asking the user included, which the words "running" count. */
  val running: Int get() = runs.count { it.status == SubagentStatus.Running }

  /** The runs waiting for a slot to start. */
  val queued: Int get() = runs.count { it.status == SubagentStatus.Queued }

  /** A subagent may be waiting on the user: the chat's question comes from one, named or not. */
  val waiting: Boolean get() = asker != SubagentAsker.Unknown

  /** Whether [call] is the agent's call starting one of Pipkin's subagents, rather than an ordinary tool call. */
  fun startsSubagent(call: TimelineItem.ToolCall): Boolean {
    if (call.toolName != SUBAGENT_TOOL || call.byUser || tool == SubagentTool.Other) return false
    return call.output == null || SubagentResult.from(call.details)?.pipkins == true
  }

  companion object {
    /** A chat with no Pi behind it, as an archived one: every call that never got a result was interrupted. */
    val None = ChatSubagents(emptyList(), SubagentAsker.Unknown, working = false)
  }
}

/**
 * The phase of the subagent `subagent` tool [call] started: its [run]'s while the extension lists it, else what the
 * call's result says, else [Queued] while the chat works and the run may yet appear, and [SubagentPhase.Interrupted]
 * once it doesn't.
 */
internal fun subagentPhase(
  call: TimelineItem.ToolCall?,
  run: SubagentRun?,
  asker: SubagentAsker,
  working: Boolean,
): SubagentPhase = when {
  run != null -> runPhase(run, asker)
  call?.output != null -> resultPhase(call)
  working -> SubagentPhase.Queued
  else -> SubagentPhase.Interrupted
}

private fun runPhase(run: SubagentRun, asker: SubagentAsker): SubagentPhase = when (run.status) {
  SubagentStatus.Queued -> SubagentPhase.Queued

  SubagentStatus.Running ->
    if ((asker as? SubagentAsker.One)?.run?.id == run.id) SubagentPhase.Asking else SubagentPhase.Running

  SubagentStatus.Done -> SubagentPhase.Done

  SubagentStatus.Failed -> SubagentPhase.Failed

  SubagentStatus.Stopped -> SubagentPhase.Stopped
}

/** A finished call's phase from its result's status, or from whether it's an error when the result names none. */
private fun resultPhase(call: TimelineItem.ToolCall): SubagentPhase = when (SubagentResult.from(call.details)?.status) {
  SubagentStatus.Failed -> SubagentPhase.Failed
  SubagentStatus.Stopped -> SubagentPhase.Stopped
  SubagentStatus.Done -> SubagentPhase.Done
  else -> if (call.isError) SubagentPhase.Failed else SubagentPhase.Done
}

/**
 * The runs of the Subagents sheet, each group in the order the extension listed them: the one whose question waits
 * on the user, the active ones, queued included, and the finished ones.
 */
internal class SubagentGroups(
  val waiting: List<SubagentRun>,
  val running: List<SubagentRun>,
  val finished: List<SubagentRun>,
)

internal fun ChatSubagents.groups(): SubagentGroups = SubagentGroups(
  waiting = runs.filter { phase(it) == SubagentPhase.Asking },
  running = runs.filter { phase(it) == SubagentPhase.Queued || phase(it) == SubagentPhase.Running },
  finished = runs.filterNot { it.status.active },
)

/**
 * What the subagent screen and the tool row know of the subagent [toolCallId] started, before reading its transcript:
 * its [agent] and [description], its [phase], its [run] while the extension lists it, the tokens its result counted,
 * where to find its transcript, and, for one interrupted while the chat's process ran, when it was cut off.
 */
internal data class SubagentFacts(
  val toolCallId: String,
  val agent: String,
  val description: String,
  val phase: SubagentPhase,
  val run: SubagentRun?,
  val resultTokens: Long?,
  val source: TranscriptSource,
  val cutOffAt: Long? = null,
)

/** Where a subagent's transcript is. */
internal sealed interface TranscriptSource {
  /** The session file at [path]. */
  data class File(val path: String) : TranscriptSource

  /**
   * Not known from the run or a result, as for a call Pi never finished: the host lists the chat's subagent files,
   * and the one the chat saved at [parentPath] started with the call is it.
   */
  data class Lookup(val parentPath: String, val toolCallId: String) : TranscriptSource

  /** The subagent ran in memory, as for a chat not yet saved, or its chat has no file to look under. */
  data object None : TranscriptSource

  /**
   * The call failed before its subagent began, as for an unknown agent, or before it saved anything, as for a model
   * without an API key, for [reason], its error.
   */
  data class NotStarted(val reason: String) : TranscriptSource
}

/**
 * The facts about the subagent the `subagent` tool [call] (null when the chat's timeline no longer has it) with id
 * [toolCallId] started, from [subagents] and the chat's session file [parentPath].
 */
internal fun subagentFacts(
  toolCallId: String,
  call: TimelineItem.ToolCall?,
  subagents: ChatSubagents,
  parentPath: String?,
): SubagentFacts {
  val run = subagents.run(toolCallId)
  val result = SubagentResult.from(call?.details)
  // A run that failed before Pi saved anything has no file to name; its error says why.
  val source = when {
    run != null -> run.sessionFile?.let(TranscriptSource::File)
      ?: run.error?.takeIf { run.status == SubagentStatus.Failed && run.messages == 0 }
        ?.let(TranscriptSource::NotStarted)
      ?: TranscriptSource.None

    result != null -> result.sessionFile?.let(TranscriptSource::File)
      ?: call?.takeIf { it.isError }?.output?.let(TranscriptSource::NotStarted)
      ?: TranscriptSource.None

    parentPath != null -> TranscriptSource.Lookup(parentPath, toolCallId)

    else -> TranscriptSource.None
  }
  val phase = subagentPhase(call, run, subagents.asker, subagents.working)
  return SubagentFacts(
    toolCallId = toolCallId,
    agent = run?.agent?.ifEmpty { null } ?: result?.agent ?: call?.args?.stringOrNull("agent").orEmpty(),
    description = run?.description?.ifEmpty { null } ?: result?.description
      ?: call?.args?.stringOrNull("description").orEmpty(),
    phase = phase,
    run = run,
    resultTokens = result?.tokens,
    source = source,
    cutOffAt = subagents.cutOff[toolCallId]?.takeIf { phase == SubagentPhase.Interrupted },
  )
}

/**
 * A transcript's turns, the tokens its replies used, and when its first message came and its last reply ended, for a
 * subagent the extension no longer lists.
 */
internal data class TranscriptStats(val turns: Int, val tokens: Long, val firstAt: Long?, val lastAt: Long?) {
  /**
   * How long the subagent took: from the first message to the last reply's end, or to [cutOffAt] when it was cut off
   * later, as in a tool call, whose end the transcript never got.
   */
  fun durationMillis(cutOffAt: Long? = null): Long? {
    val start = firstAt ?: return null
    return maxOf(lastAt ?: start, cutOffAt ?: start) - start
  }
}

internal fun transcriptStats(messages: JSONArray): TranscriptStats {
  var turns = 0
  var tokens = 0L
  var first: Long? = null
  var last: Long? = null
  for (message in messages.objects()) {
    message.optLong("timestamp").takeIf { it > 0 }?.let { timestamp ->
      if (first == null) first = timestamp
      // A reply's timestamp is when it started; Pi times how long it took.
      last = timestamp + message.optLong("durationMs")
    }
    if (message.optString("role") == "assistant") {
      turns++
      tokens += message.optJSONObject("usage")?.optLong("totalTokens") ?: 0
    }
  }
  return TranscriptStats(turns, tokens, first, last)
}

/**
 * A subagent's [messages] as its open screen has read them: Pi's context, which starts at the latest compaction's
 * summary, after the messages earlier reads had that the compaction left out. The latest read's messages start at
 * [base].
 */
internal class TranscriptHistory(val messages: List<JSONObject>, val base: Int) {
  /**
   * The messages to show: each compaction's summary marks where it happened, except one nothing came before, as when
   * the screen first read a transcript that had compacted, which then shows what an archived chat would.
   */
  fun shown(): JSONArray {
    val first = messages.summaryFrom(0)
    return JSONArray(messages.filterIndexed { index, _ -> index != first })
  }

  /**
   * The history once a read gives [read]. Every message the previous reads had stays, up to the first one the read
   * has again, so a compaction while the screen is open leaves the rows it showed in place.
   */
  fun after(read: JSONArray): TranscriptHistory {
    val latest = read.objects().toList()
    val summary = latest.summaryFrom(0) ?: return TranscriptHistory(latest, 0)
    // The compaction the previous read had: only the messages since join.
    val known = messages.summaryFrom(base)?.let(messages::get)
    if (known != null && known.sameAs(latest[summary])) {
      return TranscriptHistory(messages.subList(0, base) + latest, base)
    }
    // Messages before the new compaction's first kept one are gone from the context but still the subagent's.
    val firstKept = latest.getOrNull(summary + 1)
    val cut = firstKept?.let { kept -> (base until messages.size).firstOrNull { messages[it].sameAs(kept) } }
      ?: messages.size
    return TranscriptHistory(messages.subList(0, cut) + latest, cut)
  }

  companion object {
    /** The history of a transcript first read as [read]. */
    fun of(read: JSONArray): TranscriptHistory = TranscriptHistory(emptyList(), 0).after(read)
  }
}

/**
 * The index of the compaction summary Pi's context starts with from [start], after the system prompt it may carry
 * over; null when the messages there start with no summary.
 */
private fun List<JSONObject>.summaryFrom(start: Int): Int? {
  val index = (start until size).firstOrNull { this[it].optString("role") != "system" } ?: return null
  return index.takeIf { this[it].optString("role") == "compactionSummary" }
}

/** Whether this is [other] read again, by its role and time. */
private fun JSONObject.sameAs(other: JSONObject): Boolean {
  val timestamp = optLong("timestamp")
  return timestamp > 0 && timestamp == other.optLong("timestamp") && optString("role") == other.optString("role")
}
