package io.jeph.pipkin.agent

import io.jeph.pipkin.host.HostChannel
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostFailure
import io.jeph.pipkin.host.HostProtocol
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.objects
import io.jeph.pipkin.host.strings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal sealed interface AgentProcessState {
  data object Starting : AgentProcessState

  /** Pi is being (re)installed because the host reported `pi_version`; [progressLine] is npm's latest output. */
  data class Installing(val progressLine: String?) : AgentProcessState

  data object Ready : AgentProcessState

  /** Pi ended without being asked to; the timeline shows an exit banner. */
  data class Exited(val code: Int?, val signal: String?, val stderr: String) : AgentProcessState

  /**
   * Pi could not start. [failure] is set when Termux could not run the host (onboarding may fix it);
   * [code] is the host's refusal code (`cwd_missing`, `pi_version`, …) when it refused.
   */
  data class Failed(val message: String, val failure: HostFailure? = null, val code: String? = null) :
    AgentProcessState
}

/** A reply to a dialog `extension_ui_request` (Pi `docs/rpc-extension-ui.md`). */
internal sealed interface UiAnswer {
  /** The chosen option (`select`) or the entered text (`input`, `editor`). */
  data class Value(val value: String) : UiAnswer

  /** The `confirm` answer. */
  data class Confirmed(val confirmed: Boolean) : UiAnswer

  /** The dialog was dismissed; any method. */
  data object Cancelled : UiAnswer
}

/**
 * One `pi --mode rpc` for one chat, reached through a `host.mjs` channel. Starts on construction;
 * [restart] reconnects in place (exit banner Restart, failure Retry), [close] ends it for good.
 * Pi stops when the channel closes. Command wrappers throw [RpcError].
 */
internal class AgentProcess(
  private val hostClient: HostClient,
  private val keepAlive: KeepAlive?,
  override val cwd: String,
  sessionPath: String?,
  scope: CoroutineScope,
  private val onSessionChanged: () -> Unit = {},
) : ManagedProcess {
  private class Connection(val channel: HostChannel) {
    val rpc = RpcClient(channel::send)

    @Volatile
    var closing = false

    @Volatile
    var rejection: HostRejected? = null

    /** The host's `pipkin_exit` record, once Pi ended. */
    @Volatile
    var exit: JSONObject? = null

    fun close() {
      closing = true
      channel.close()
    }
  }

  private val job = SupervisorJob(scope.coroutineContext[Job])
  private val processScope = CoroutineScope(scope.coroutineContext + job)
  private val stateFlow = MutableStateFlow<AgentProcessState>(AgentProcessState.Starting)
  private val sessionFileState = MutableStateFlow(sessionPath)
  private val sessionNameState = MutableStateFlow<String?>(null)
  private val contextUsageState = MutableStateFlow<ContextUsage?>(null)
  private val contextUsageRequests = Channel<Unit>(Channel.CONFLATED)

  @Volatile
  private var connection: Connection? = null
  private var startJob: Job? = null

  val timeline = ChatTimeline()

  /**
   * [ChatTimeline.state] for the screen, at most once per [STREAM_UPDATE_MILLIS] and always ending on the latest
   * state. A change after a quiet spell shows at once; while Pi streams, the deltas in between are combined.
   */
  val screenTimeline: StateFlow<TimelineState> = timeline.state
    .transform { state ->
      emit(state)
      delay(STREAM_UPDATE_MILLIS)
    }.stateIn(processScope + Dispatchers.Default, SharingStarted.Eagerly, timeline.state.value)

  val state: StateFlow<AgentProcessState> = stateFlow.asStateFlow()

  override val sessionFile: StateFlow<String?> = sessionFileState.asStateFlow()

  /** `get_state.sessionName`, kept current by `set_session_name` and `session_info_changed`. */
  val sessionName: StateFlow<String?> = sessionNameState.asStateFlow()

  /** How full the context window is, from `get_session_stats`; null until Pi first answered. */
  val contextUsage: StateFlow<ContextUsage?> = contextUsageState.asStateFlow()

  /** The timeline's run state while Pi is up; false once it exited or failed. */
  override val busy: StateFlow<Boolean> = combine(timeline.state, stateFlow) { timelineState, processState ->
    timelineState.busy && processState == AgentProcessState.Ready
  }.stateIn(processScope, SharingStarted.Eagerly, false)

  init {
    // One reader, conflated: a burst of turn ends costs one get_session_stats, and answers never land out of order.
    processScope.launch {
      contextUsageRequests.consumeEach {
        try {
          contextUsageState.value = ContextUsage.from(call("get_session_stats"))
        } catch (_: RpcError) {
          // Pi stopped or is restarting; the meter keeps its last reading until the next refresh.
        }
      }
    }
    // Last in construction: an immediate dispatcher starts connecting inline.
    startJob = processScope.launch { connect() }
  }

  /** Reconnects with the same cwd and session file, keeping the timeline until the new history arrives. */
  fun restart() {
    startJob?.cancel()
    connection?.close()
    connection = null
    startJob = processScope.launch { connect() }
  }

  override fun close() {
    startJob?.cancel()
    connection?.close()
    connection = null
    job.cancel()
  }

  /**
   * Returns Pi's `disposition`: `started`, `queued` or `handled`. Pi answers a slash command only once an
   * extension command finished, which can wait on the user's answers, so those wait without a deadline.
   */
  suspend fun prompt(message: String, images: List<ImagePart> = emptyList()): String {
    val params = JSONObject()
      .put("message", message)
      // Pi only reads streamingBehavior while a run is streaming, so steering is safe to always request
      // and avoids a rejection when a run starts between the UI's busy check and this command.
      .put("streamingBehavior", "steer")
    if (images.isNotEmpty()) {
      params.put(
        "images",
        JSONArray(
          images.map { JSONObject().put("type", "image").put("data", it.data).put("mimeType", it.mimeType) },
        ),
      )
    }
    val timeout = if (message.startsWith("/")) null else RpcClient.TIMEOUT_MILLIS
    return call("prompt", params, timeout).optString("disposition")
  }

  override suspend fun abort() {
    call("abort")
  }

  /** `get_state` data; also refreshes [sessionFile], [sessionName] and the timeline's model and thinking level. */
  suspend fun getState(): JSONObject = call("get_state").also(::applyState)

  suspend fun getMessages(): JSONArray = call("get_messages").optJSONArray("messages") ?: JSONArray()

  /** Pi Model objects (`provider`, `id`, `name`, `input`, …). */
  suspend fun availableModels(): List<JSONObject> = call("get_available_models").optJSONArray("models").objects()

  suspend fun setModel(provider: String, modelId: String) {
    val model = call("set_model", JSONObject().put("provider", provider).put("modelId", modelId))
    timeline.setModel(model)
    // Pi clamps the thinking level to what the new model supports.
    syncState()
    // The new model has its own context window.
    refreshContextUsage()
  }

  suspend fun availableThinkingLevels(): List<String> =
    call("get_available_thinking_levels").optJSONArray("levels").strings()

  suspend fun setThinkingLevel(level: String) {
    call("set_thinking_level", JSONObject().put("level", level))
    timeline.setThinkingLevel(level)
    syncState()
  }

  suspend fun setSessionName(name: String) {
    call("set_session_name", JSONObject().put("name", name))
    sessionNameState.value = name
    onSessionChanged()
  }

  /** Empties Pi's steering and follow-up queues and returns their texts. */
  suspend fun clearQueue(): List<String> {
    val data = call("clear_queue")
    return data.optJSONArray("steering").strings() + data.optJSONArray("followUp").strings()
  }

  /** Answers an `extension_ui_request` dialog and clears it from the timeline. False when Pi is no longer reachable. */
  suspend fun respondUi(id: String, answer: UiAnswer): Boolean {
    timeline.clearPendingUi()
    val response = JSONObject().put("type", "extension_ui_response").put("id", id)
    when (answer) {
      is UiAnswer.Value -> response.put("value", answer.value)
      is UiAnswer.Confirmed -> response.put("confirmed", answer.confirmed)
      UiAnswer.Cancelled -> response.put("cancelled", true)
    }
    val channel = connection?.channel ?: return false
    return try {
      channel.send(response)
      true
    } catch (_: IOException) {
      false
    }
  }

  /** Re-reads state and history after Pi moved this process to another session file (fork, clone). */
  suspend fun reloadSession() {
    applyState(call("get_state"))
    timeline.seed(getMessages())
    refreshContextUsage()
    onSessionChanged()
  }

  /** Connects; a `pi_version` refusal installs the pinned Pi once and retries once. */
  private suspend fun connect() {
    var installed = false
    while (true) {
      val rejection = attempt() ?: return
      if (rejection.code != PI_VERSION || installed) {
        stateFlow.value = AgentProcessState.Failed(rejection.error, code = rejection.code)
        return
      }
      installed = true
      if (!install()) return
    }
  }

  /** One connection; returns the host's refusal, or null once [state] is Ready, Exited or Failed. */
  private suspend fun attempt(): HostRejected? {
    stateFlow.value = AgentProcessState.Starting
    val channel = try {
      hostClient.openRpc(cwd, sessionFileState.value)
    } catch (failure: HostFailure) {
      stateFlow.value = AgentProcessState.Failed(failure.message.orEmpty(), failure)
      return null
    }
    val current = Connection(channel)
    connection = current
    // Off the main thread: a streamed reply delivers dozens of events a second.
    processScope.launch(Dispatchers.Default) { read(current) }
    return try {
      applyState(current.rpc.call("get_state"))
      timeline.seed(current.rpc.call("get_messages").optJSONArray("messages") ?: JSONArray())
      stateFlow.value = AgentProcessState.Ready
      refreshContextUsage()
      null
    } catch (error: RpcError) {
      current.close()
      val rejection = current.rejection ?: error.cause as? HostRejected
      // An exit while starting was already reported by the reader.
      if (rejection == null && stateFlow.value !is AgentProcessState.Exited) {
        stateFlow.value = AgentProcessState.Failed(error.error)
      }
      rejection
    }
  }

  /** Installs the pinned Pi once after a `pi_version` refusal; false (with [state] Failed) when that failed. */
  private suspend fun install(): Boolean {
    stateFlow.value = AgentProcessState.Installing(null)
    try {
      keepAlive?.acquire("install Pi").use {
        hostClient.ensure { line -> stateFlow.value = AgentProcessState.Installing(line) }
      }
      return true
    } catch (failure: HostFailure) {
      stateFlow.value = AgentProcessState.Failed(failure.message.orEmpty(), failure)
    } catch (rejected: HostRejected) {
      stateFlow.value = AgentProcessState.Failed(rejected.error, code = rejected.code)
    } catch (error: JSONException) {
      stateFlow.value = AgentProcessState.Failed(error.message ?: "Installing the Pipkin agent failed.")
    }
    return false
  }

  private suspend fun read(current: Connection) {
    try {
      current.channel.lines.collect { line -> dispatch(current, line) }
      if (!current.closing && current.rejection == null && connection === current) reportExit(current.exit)
    } finally {
      // After reportExit, so a command failing on this exit sees the Exited state.
      current.rpc.close(RpcClient.NOT_RUNNING)
    }
  }

  /** Pi ended unasked; [exit] is the host's `pipkin_exit`, null when the connection just dropped. */
  private fun reportExit(exit: JSONObject?) {
    val code = if (exit == null || exit.isNull("code")) null else exit.optInt("code")
    val stderr = exit?.optString("stderr").orEmpty()
    stateFlow.value = AgentProcessState.Exited(code, exit?.opt("signal") as? String, stderr)
    timeline.showExit(code, stderr)
  }

  private fun dispatch(current: Connection, line: JSONObject) {
    when (line.optString("type")) {
      "response" -> current.rpc.onResponse(line)

      HostProtocol.EXIT -> current.exit = line

      HostProtocol.RESULT -> HostRejected.from(line)?.let { rejection ->
        current.rejection = rejection
        current.rpc.close(rejection.error, rejection)
      }

      else -> onEvent(line)
    }
  }

  private fun onEvent(event: JSONObject) {
    timeline.apply(event)
    when (event.optString("type")) {
      // Each turn adds a response and tool results to the context; compaction shrinks it.
      "turn_end", "compaction_end" -> refreshContextUsage()

      "agent_settled" -> {
        refreshContextUsage()
        onSessionChanged()
      }

      "session_info_changed" -> {
        sessionNameState.value = event.opt("name") as? String
        onSessionChanged()
      }

      "thinking_level_changed" -> timeline.setThinkingLevel(event.opt("level") as? String)
    }
  }

  private fun refreshContextUsage() {
    contextUsageRequests.trySend(Unit)
  }

  private fun applyState(data: JSONObject) {
    (data.opt("sessionFile") as? String)?.let { sessionFileState.value = it }
    sessionNameState.value = data.opt("sessionName") as? String
    // With no usable model Pi 1.1.0 reports a placeholder whose provider is "unknown" rather than omitting it.
    timeline.setModel(data.optJSONObject("model")?.takeUnless { it.optString("provider") == "unknown" })
    timeline.setThinkingLevel(data.opt("thinkingLevel") as? String)
  }

  /** Best-effort `get_state` after a change Pi may have adjusted; events and the next change correct a miss. */
  private suspend fun syncState() {
    try {
      getState()
    } catch (_: RpcError) {
      // The change itself succeeded; a stale level or model label is corrected by the next get_state.
    }
  }

  /** Pi's RPC [command]; throws [RpcError]. A null [timeoutMillis] waits until Pi answers or exits. */
  suspend fun call(
    command: String,
    params: JSONObject = JSONObject(),
    timeoutMillis: Long? = RpcClient.TIMEOUT_MILLIS,
  ): JSONObject {
    val rpc = connection?.rpc ?: throw RpcError(command, RpcClient.NOT_RUNNING)
    return rpc.call(command, params, timeoutMillis)
  }

  private companion object {
    const val PI_VERSION = "pi_version"
  }
}
