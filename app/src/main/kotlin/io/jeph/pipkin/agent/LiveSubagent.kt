package io.jeph.pipkin.agent

import io.jeph.pipkin.host.objects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
   * Takes a message from the extension: a followed run's `live` log so far, which starts its transcript, or one of its
   * `event`s. A run no screen follows any more is left alone.
   */
  fun onMessage(data: JSONObject) {
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

/**
 * Timeline states for a screen, at most once per [STREAM_UPDATE_MILLIS] and always ending on the latest state. A change
 * after a quiet spell shows at once; while Pi streams, the deltas in between are combined. Each state's
 * [TimelineState.stream] is built here, so collect it off the main thread.
 */
internal fun Flow<TimelineState>.forScreen(): Flow<TimelineState> = transform { state ->
  emit(state.withStream())
  delay(STREAM_UPDATE_MILLIS)
}
