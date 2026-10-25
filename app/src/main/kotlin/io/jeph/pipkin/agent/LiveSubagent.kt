package io.jeph.pipkin.agent

import io.jeph.pipkin.host.objects
import io.jeph.pipkin.host.strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * A subagent's transcript built from its session events, as the `pipkin-subagents` extension sends them while a
 * subagent screen follows the run. The events have the shape of Pi's RPC events, so a [ChatTimeline] builds the rows
 * as for a chat. Each event's time, as the extension saw it, stands in for the timeline's clock, so a log sent mid-run
 * times the run as it went rather than from when the screen opened.
 */
internal class LiveSubagent {
  /** The time of the event being applied; only the channel's reader applies events. */
  private var eventAt = 0L

  val timeline = ChatTimeline(clock = { eventAt })

  /** The rows for the screen, at most once per [STREAM_UPDATE_MILLIS]; see [forScreen]. */
  val screenStates: Flow<TimelineState> = timeline.state.forScreen().flowOn(Dispatchers.Default)

  /** Applies one log entry, `{"at": …, "event": {…}}`. */
  fun apply(entry: JSONObject) {
    val event = entry.optJSONObject("event") ?: return
    eventAt = entry.optLong("at")
    timeline.apply(event)
  }
}

/**
 * The runs of one chat's `pipkin-subagents` extension that subagent screens follow, by run id, each with its
 * [LiveSubagent] once the extension sent the log so far. [send] puts a message on the extension's channel topic;
 * [scope] sends the ones nothing waits for.
 */
internal class LiveSubagents(private val scope: CoroutineScope, private val send: suspend (JSONObject) -> Unit) {
  private val runs = MutableStateFlow<Map<String, LiveSubagent?>>(emptyMap())

  /** The extension's answers to [takeSteers]: a run's id and the steers it took back. */
  private val unsteered = MutableSharedFlow<JSONObject>(extraBufferCapacity = UNSTEERED_BUFFER)

  /** Run [runId]'s transcript once the extension sent its log after [follow]; null until then, and after [clear]. */
  fun of(runId: String): Flow<LiveSubagent?> = runs.map { it[runId] }.distinctUntilChanged()

  /** Asks the extension for run [runId]'s log so far, then each of its events, until [unfollow]. */
  suspend fun follow(runId: String) {
    runs.update { it + (runId to null) }
    send(JSONObject().put("action", "follow").put("id", runId))
  }

  /** Stops run [runId]'s events and forgets its transcript, as its screen closes. */
  fun unfollow(runId: String) {
    runs.update { it - runId }
    scope.launch { send(JSONObject().put("action", "unfollow").put("id", runId)) }
  }

  /**
   * Takes back the steers followed run [runId] hasn't read, oldest first, so they never reach it; empty when it read
   * them all, or the extension didn't answer. Pi may have taken them for the subagent's next turn just before and still
   * delivers those, so, as the chat's Edit does, this waits for that turn to start and leaves out every message the
   * subagent started meanwhile.
   */
  suspend fun takeSteers(runId: String): List<String> {
    val timeline = runs.value[runId]?.timeline ?: return emptyList()
    val mark = timeline.startedUserMessages.mark()
    val answer = withTimeoutOrNull(ANSWER_TIMEOUT_MILLIS) {
      unsteered
        .onSubscription { send(JSONObject().put("action", "unsteer").put("id", runId)) }
        .first { it.optString("id") == runId }
    } ?: return emptyList()
    // A screen closing meanwhile stops the events that end the wait; what started by then is still left out.
    withTimeoutOrNull(ANSWER_TIMEOUT_MILLIS) { timeline.state.first { !it.queueInFlight } }
    val kept = answer.optJSONArray("steering").strings().toMutableList()
    timeline.startedUserMessages.textsSince(mark).forEach(kept::remove)
    return kept
  }

  /**
   * Takes a message from the extension: a followed run's `live` log so far, which starts its transcript, one of its
   * `event`s, or the steers `unsteered` from a run. A run no screen follows any more is left alone.
   */
  fun onMessage(data: JSONObject) {
    data.optJSONObject("unsteered")?.let(unsteered::tryEmit)
    data.optJSONObject("event")?.let { entry -> runs.value[entry.optString("id")]?.apply(entry) }
    val log = data.optJSONObject("live") ?: return
    val runId = log.optString("id")
    if (runId !in runs.value) return
    val live = LiveSubagent()
    log.optJSONArray("entries").objects().forEach(live::apply)
    runs.update { if (runId in it) it + (runId to live) else it }
  }

  /** Forgets every run, as the chat's Pi goes away: the next one follows none. */
  fun clear() {
    runs.value = emptyMap()
  }
}

/** How long [LiveSubagents.takeSteers] waits for the extension's answer, and for a turn Pi started meanwhile. */
private const val ANSWER_TIMEOUT_MILLIS = 10_000L

/** Answers to [LiveSubagents.takeSteers] kept until their caller reads them; a few at most are ever in flight. */
private const val UNSTEERED_BUFFER = 8

/**
 * Timeline states for a screen, at most once per [STREAM_UPDATE_MILLIS] and always ending on the latest state. A change
 * after a quiet spell shows at once; while Pi streams, the deltas in between are combined. Each state's
 * [TimelineState.stream] is built here, so collect it off the main thread.
 */
internal fun Flow<TimelineState>.forScreen(): Flow<TimelineState> = transform { state ->
  emit(state.withStream())
  delay(STREAM_UPDATE_MILLIS)
}
