package io.jeph.pipkin.ui.subagents

import androidx.compose.runtime.Immutable
import io.jeph.pipkin.agent.SubagentAsker
import io.jeph.pipkin.agent.SubagentResult
import io.jeph.pipkin.agent.SubagentRun
import io.jeph.pipkin.agent.SubagentStatus
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.host.objects
import io.jeph.pipkin.ui.chat.stringOrNull
import org.json.JSONArray

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

/**
 * What a chat knows of its subagents now: the [runs] its extension lists, whose question the chat's card shows
 * ([asker]), and whether the chat is [working], which tells a call still waiting to start from one Pi left behind.
 */
@Immutable
internal class ChatSubagents(val runs: List<SubagentRun>, val asker: SubagentAsker, val working: Boolean) {
  /** The run [toolCallId] started, while the extension still lists it. */
  fun run(toolCallId: String): SubagentRun? = runs.firstOrNull { it.toolCallId == toolCallId }

  fun phase(call: TimelineItem.ToolCall): SubagentPhase = subagentPhase(call, run(call.id), asker, working)

  fun phase(run: SubagentRun): SubagentPhase = runPhase(run, asker)

  /** The runs queued or running, which the Subagents tab counts. */
  val active: Int get() = runs.count { it.status.active }

  /** A subagent may be waiting on the user: the chat's question comes from one, named or not. */
  val waiting: Boolean get() = asker != SubagentAsker.Unknown

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

/** A finished call's phase from its result; one Pi refused before the extension ran has no details, only its error. */
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
 * and where to find its transcript.
 */
internal data class SubagentFacts(
  val toolCallId: String,
  val agent: String,
  val description: String,
  val phase: SubagentPhase,
  val run: SubagentRun?,
  val resultTokens: Long?,
  val source: TranscriptSource,
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
  val source = when {
    run != null -> run.sessionFile?.let(TranscriptSource::File) ?: TranscriptSource.None
    result != null -> result.sessionFile?.let(TranscriptSource::File) ?: TranscriptSource.None
    parentPath != null -> TranscriptSource.Lookup(parentPath, toolCallId)
    else -> TranscriptSource.None
  }
  return SubagentFacts(
    toolCallId = toolCallId,
    agent = run?.agent?.ifEmpty { null } ?: result?.agent ?: call?.args?.stringOrNull("agent").orEmpty(),
    description = run?.description?.ifEmpty { null } ?: result?.description
      ?: call?.args?.stringOrNull("description").orEmpty(),
    phase = subagentPhase(call, run, subagents.asker, subagents.working),
    run = run,
    resultTokens = result?.tokens,
    source = source,
  )
}

/**
 * A transcript's turns, the tokens its replies used, and how long it took from its first message to its last, for a
 * subagent the extension no longer lists.
 */
internal data class TranscriptStats(val turns: Int, val tokens: Long, val durationMillis: Long?)

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
  val start = first
  val end = last
  return TranscriptStats(turns, tokens, if (start != null && end != null) end - start else null)
}
