package io.jeph.pipkin.agent

import android.os.SystemClock
import android.util.Log
import io.jeph.pipkin.data.SessionRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal sealed interface AgentProcessState {
  /** Pi is on its way up: nothing can reach it yet, but it will answer shortly. */
  val starting: Boolean get() = this == Starting || this is Installing

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
@Suppress("TooManyFunctions") // One typed wrapper per Pi RPC command, beside the connection they share.
internal class AgentProcess(
  private val hostClient: HostClient,
  private val keepAlive: KeepAlive?,
  override val cwd: String,
  sessionPath: String?,
  scope: CoroutineScope,
  private val sessions: SessionRepository,
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
  private val extensionTitleState = MutableStateFlow(sessionPath?.let { sessions.extensionTitles[it] })
  private val extensionStatusesState = MutableStateFlow(sessionPath?.let { sessions.extensionStatuses[it] }.orEmpty())
  private val extensionWidgetsState = MutableStateFlow(sessionPath?.let { sessions.extensionWidgets[it] }.orEmpty())
  private val contextUsageState = MutableStateFlow<ContextUsage?>(null)
  private val contextUsageRequests = Channel<Unit>(Channel.CONFLATED)

  /** Pi's setTitle requests and renames so far, so a cancelled [moveSession] knows whether the title changed since. */
  @Volatile
  private var extensionTitleChanges = 0

  /**
   * The status and widget keys Pi set or cleared since [moveSession] took them down, so a cancelled move keeps those.
   * Guarded, with every write to the statuses and widgets, by [extensionUiLock]: Pi's reader thread sets them while a
   * move takes them down or puts them back.
   */
  private val extensionStatusKeysChanged = HashSet<String>()
  private val extensionWidgetKeysChanged = HashSet<String>()
  private val extensionUiLock = Any()

  @Volatile
  private var connection: Connection? = null
  private var startJob: Job? = null

  /** When [onConnectionEnded] last reconnected on its own ([SystemClock.elapsedRealtime]); null before the first time. */
  @Volatile
  private var lastAutoReconnectAt: Long? = null

  /** Whether [connect] restored the session's saved notices; once, before the first connection. */
  private var noticesRestored = false

  /** Saves each notice as it arrives, so the file holds every notice the screen showed. */
  val timeline = ChatTimeline(
    onNoticesChanged = { notices -> sessionFileState.value?.let { sessions.notices.put(it, notices) } },
  )

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

  /**
   * The title an extension set with `ctx.ui.setTitle`, shown in place of the chat's name. It lasts until a rename or a
   * move to another session, and across the Pi restarts Pipkin makes, through [SessionRepository.extensionTitles];
   * extensions may set another on `session_start`. A rename clears it as in Pi's terminal UI, which puts its own title
   * back when the name changes.
   */
  val extensionTitle: StateFlow<String?> = extensionTitleState.asStateFlow()

  /**
   * The text extensions set with `ctx.ui.setStatus`, by status key, with Pi's ANSI styling left in. Like the title, the
   * statuses last until a move to another session, where Pi's terminal UI clears them too, and across the Pi restarts
   * Pipkin makes, through [SessionRepository.extensionStatuses].
   */
  val extensionStatuses: StateFlow<Map<String, String>> = extensionStatusesState.asStateFlow()

  /**
   * The widgets extensions set with `ctx.ui.setWidget`, by widget key, in the order the composer shows them. Like the
   * statuses, they last until a move to another session and across the Pi restarts Pipkin makes, through
   * [SessionRepository.extensionWidgets].
   */
  val extensionWidgets: StateFlow<Map<String, ExtensionWidget>> = extensionWidgetsState.asStateFlow()

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
  override fun restart() {
    park()
    startJob = processScope.launch { connect() }
  }

  /**
   * Ends Pi but keeps the timeline, so [restart] shows the history at once. Starting until then: the timeline is only
   * a copy of Pi's history while Pi is down, and nothing can be sent to it.
   */
  override fun park() {
    keepExtensionUi()
    startJob?.cancel()
    connection?.close()
    connection = null
    timeline.clearDialogs()
    stateFlow.value = AgentProcessState.Starting
  }

  override fun close() {
    keepExtensionUi()
    startJob?.cancel()
    connection?.close()
    connection = null
    job.cancel()
  }

  /**
   * Returns Pi's `disposition`: `started`, `queued` or `handled`. Pi answers only after the extensions' `input` and
   * `before_agent_start` handlers, a slash command's whole run, and any compaction due first; handlers can wait on the
   * user's answers, so this waits without a deadline. A Pi that stops answering is caught by [resync].
   */
  suspend fun prompt(message: String, images: List<ImagePart> = emptyList()): String {
    val params = messageParams(message, images)
      // Pi only reads streamingBehavior while a run is streaming, so steering is safe to always request
      // and avoids a rejection when a run starts between the UI's busy check and this command.
      .put("streamingBehavior", "steer")
    return call("prompt", params, timeoutMillis = null).optString("disposition")
  }

  /**
   * Queues [message] as a steer, even while Pi is idle, since the next run Pi starts takes the queued steers into
   * its first turn. Returns Pi's `disposition`: `queued`, or `handled` when an extension consumed it. Pi answers after
   * the extensions' `input` handlers, which can wait on the user, so this waits without a deadline.
   */
  suspend fun steer(message: String, images: List<ImagePart> = emptyList()): String =
    call("steer", messageParams(message, images), timeoutMillis = null).optString("disposition")

  override suspend fun abort() {
    call("abort")
  }

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
    sessions.refresh()
  }

  /** Empties Pi's steering and follow-up queues and returns their texts. */
  suspend fun clearQueue(): List<String> {
    val data = call("clear_queue")
    return data.optJSONArray("steering").strings() + data.optJSONArray("followUp").strings()
  }

  /** Answers dialog [id] and drops it from the timeline. False when Pi is no longer reachable. */
  suspend fun respondUi(id: String, answer: UiAnswer): Boolean {
    timeline.dismissDialog(id)
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

  /**
   * Sends [command], which moves this process to another session file (fork, clone) unless an extension cancels it,
   * then re-reads state and history. Returns Pi's answer, or null when cancelled. Waits without a deadline: Pi answers
   * after the extensions' `session_before_fork` handlers, which can wait on the user.
   *
   * The old session's extension title, statuses and widgets go first, since Pi starts the new session's extensions,
   * which may set their own, before it answers; [SessionRepository] keeps them for the session left behind. A move that
   * doesn't happen puts back whichever of them Pi didn't change meanwhile. Those extensions notify before Pi answers
   * too, so their notices wait for the new history. The new session starts with the notices that followed messages it
   * kept, unless it has saved notices of its own.
   */
  suspend fun moveSession(command: String, params: JSONObject = JSONObject()): JSONObject? {
    val left = takeDownExtensionUi()
    val previousFile = sessionFileState.value
    timeline.awaitHistory()
    val data = try {
      call(command, params, timeoutMillis = null)
    } catch (error: RpcError) {
      putBack(left)
      timeline.keepHistory()
      throw error
    }
    if (data.optBoolean("cancelled")) {
      putBack(left)
      timeline.keepHistory()
      return null
    }
    previousFile?.let { keepExtensionUi(it, left) }
    applyState(call("get_state"))
    val messages = getMessages()
    val path = sessionFileState.value
    val saved = path?.let { withContext(Dispatchers.IO) { sessions.notices.load(it) } }.orEmpty()
    val kept = messageTimestamps(messages)
    timeline.restoreNotices { placed -> saved.ifEmpty { placed.filter { it.after == null || it.after in kept } } }
    path?.let { sessions.notices.put(it, timeline.savedNotices()) }
    timeline.seed(messages)
    refreshContextUsage()
    sessions.refresh()
    return data
  }

  /**
   * Brings the timeline in line with Pi after Pipkin was away, since events can go missing while it is in the
   * background. The `get_state` write also surfaces a connection that died unnoticed, which [read] then replaces.
   * A Pi that cannot answer `get_state` in [RESYNC_TIMEOUT_MILLIS] is unreachable, so this process reconnects; the
   * session file keeps the history.
   */
  suspend fun resync() {
    if (stateFlow.value != AgentProcessState.Ready) return
    val eventsBefore = timeline.appliedEvents
    val data = try {
      call("get_state", timeoutMillis = RESYNC_TIMEOUT_MILLIS)
    } catch (error: RpcError) {
      Log.w(TAG, "Re-sync get_state failed (timedOut=${error.timedOut}): ${error.error}")
      if (error.timedOut && stateFlow.value == AgentProcessState.Ready) restart()
      return
    }
    applyState(data)
    val shown = timeline.state.value
    val piWorking = data.optBoolean("isStreaming") || data.optBoolean("isCompacting")
    // Events that arrived meanwhile show the stream is live, and a dialog means Pi waits on the user.
    if (piWorking || !shown.busy || shown.pendingUi != null || timeline.appliedEvents != eventsBefore) return
    val messages = try {
      getMessages()
    } catch (error: RpcError) {
      Log.w(TAG, "Re-sync get_messages failed: ${error.error}")
      null
    }
    // A run that started while get_messages was answered has delivered events; its stream supersedes this reload.
    if (messages == null || !timeline.seed(messages, ifNoEventsSince = eventsBefore)) return
    Log.w(TAG, "Pi was idle but the chat still showed a run; reloaded its history")
    refreshContextUsage()
    sessions.refresh()
  }

  /** Connects; a `pi_version` refusal installs the pinned Pi once and retries once. */
  private suspend fun connect() {
    restoreNotices()
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

  /** Before the first connection, so a notice Pi sends while starting joins the saved ones instead of replacing them. */
  private suspend fun restoreNotices() {
    if (noticesRestored) return
    sessionFileState.value?.let { path ->
      val saved = withContext(Dispatchers.IO) { sessions.notices.load(path) }
      timeline.restoreNotices { saved }
    }
    noticesRestored = true
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
      if (!current.closing && current.rejection == null && connection === current) onConnectionEnded(current)
    } finally {
      // After onConnectionEnded, so a command failing on this exit sees the Exited state.
      current.rpc.close(RpcClient.NOT_RUNNING)
    }
  }

  /**
   * The connection ended unasked. Without the host's `pipkin_exit` the connection dropped rather than Pi exiting,
   * so a connection that was working reconnects and reloads the history; Pi stopped with it, so no run survives.
   * Otherwise, or on a second drop within [AUTO_RECONNECT_INTERVAL_MILLIS] (so a failing host cannot loop), the
   * timeline shows an exit banner.
   */
  private fun onConnectionEnded(current: Connection) {
    val exit = current.exit
    val now = SystemClock.elapsedRealtime()
    val recentlyReconnected = lastAutoReconnectAt?.let { now - it < AUTO_RECONNECT_INTERVAL_MILLIS } ?: false
    if (exit == null && stateFlow.value == AgentProcessState.Ready && !recentlyReconnected) {
      Log.w(TAG, "Connection to Pi dropped without an exit record; reconnecting")
      lastAutoReconnectAt = now
      timeline.clearDialogs()
      processScope.launch(Dispatchers.Main) { restart() }
      return
    }
    val code = if (exit == null || exit.isNull("code")) null else exit.optInt("code")
    val signal = exit?.opt("signal") as? String
    Log.w(TAG, "Pi stopped: exitRecord=${exit != null} code=$code signal=$signal")
    val stderr = exit?.optString("stderr").orEmpty()
    stateFlow.value = AgentProcessState.Exited(code, signal, stderr)
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

      // Pi saves each message as its message_end goes out; a new chat's first one creates its session file.
      "message_end" -> if (event.optJSONObject("message")?.optString("role") == "user") {
        sessionFileState.value?.let(sessions::refreshIfUnlisted)
      }

      "agent_settled" -> {
        refreshContextUsage()
        sessions.refresh()
      }

      "session_info_changed" -> {
        sessionNameState.value = event.opt("name") as? String
        setExtensionTitle(null)
        sessions.refresh()
      }

      "extension_ui_request" -> extensionUi(event)

      "thinking_level_changed" -> timeline.setThinkingLevel(event.opt("level") as? String)
    }
  }

  private fun extensionUi(event: JSONObject) {
    when (event.optString("method")) {
      "setTitle" -> {
        setExtensionTitle(event.optString("title").takeIf { it.isNotBlank() })
        return
      }

      "setStatus" -> {
        setExtensionStatus(event)
        return
      }

      "setWidget" -> {
        setExtensionWidget(event)
        return
      }
    }
    // Pi answers a timed-out dialog itself and says nothing, so the dialog goes when Pi stops waiting on it.
    val timeout = dialogTimeoutMillis(event)?.takeIf { it > 0 } ?: return
    val id = event.optString("id")
    processScope.launch {
      delay(timeout)
      timeline.dismissDialog(id)
    }
  }

  /** Pi's reader thread is the only caller, so the count has a single writer. */
  private fun setExtensionTitle(title: String?) {
    extensionTitleChanges++
    extensionTitleState.value = title
  }

  /** Sets or, without `statusText`, clears one key's status. */
  private fun setExtensionStatus(event: JSONObject) {
    val key = event.opt("statusKey") as? String ?: return
    val text = event.opt("statusText") as? String
    synchronized(extensionUiLock) {
      extensionStatusKeysChanged += key
      val statuses = extensionStatusesState.value
      extensionStatusesState.value = if (text == null) statuses - key else statuses + (key to text)
    }
  }

  private fun setExtensionWidget(event: JSONObject) {
    synchronized(extensionUiLock) {
      extensionWidgetsState.value = withWidget(extensionWidgetsState.value, event) ?: return
      extensionWidgetKeysChanged += event.getString("widgetKey")
    }
  }

  /** The extension title, statuses and widgets [moveSession] took down, with the title's change count then. */
  private class LeftExtensionUi(
    val title: String?,
    val titleChanges: Int,
    val statuses: Map<String, String>,
    val widgets: Map<String, ExtensionWidget>,
  )

  private fun takeDownExtensionUi(): LeftExtensionUi {
    val left = synchronized(extensionUiLock) {
      extensionStatusKeysChanged.clear()
      extensionWidgetKeysChanged.clear()
      LeftExtensionUi(
        extensionTitleState.value,
        extensionTitleChanges,
        extensionStatusesState.value,
        extensionWidgetsState.value,
      ).also {
        extensionStatusesState.value = emptyMap()
        extensionWidgetsState.value = emptyMap()
      }
    }
    extensionTitleState.value = null
    return left
  }

  /** Puts back what [takeDownExtensionUi] took down, except the title or the status and widget keys Pi changed since. */
  private fun putBack(left: LeftExtensionUi) {
    if (extensionTitleChanges == left.titleChanges) extensionTitleState.value = left.title
    synchronized(extensionUiLock) {
      extensionStatusesState.value =
        left.statuses.filterKeys { it !in extensionStatusKeysChanged } + extensionStatusesState.value
      extensionWidgetsState.value =
        left.widgets.filterKeys { it !in extensionWidgetKeysChanged } + extensionWidgetsState.value
    }
  }

  /** Hands the extension UI to [SessionRepository] for the next Pi on this session, whichever process that is. */
  private fun keepExtensionUi() {
    val sessionFile = sessionFileState.value ?: return
    val ui = LeftExtensionUi(
      extensionTitleState.value,
      extensionTitleChanges,
      extensionStatusesState.value,
      extensionWidgetsState.value,
    )
    keepExtensionUi(sessionFile, ui)
  }

  private fun keepExtensionUi(sessionFile: String, left: LeftExtensionUi) {
    sessions.extensionTitles.keep(sessionFile, left.title)
    sessions.extensionStatuses.keep(sessionFile, left.statuses.ifEmpty { null })
    sessions.extensionWidgets.keep(sessionFile, left.widgets.ifEmpty { null })
  }

  private fun refreshContextUsage() {
    contextUsageRequests.trySend(Unit)
  }

  private fun applyState(data: JSONObject) {
    (data.opt("sessionFile") as? String)?.let { path ->
      val named = sessionFileState.value == null
      sessionFileState.value = path
      // A new chat learns its session file here, so the notices it showed before are saved now. Set first, so a notice
      // arriving meanwhile is saved by the timeline's callback instead.
      if (named) sessions.notices.put(path, timeline.savedNotices())
    }
    sessionNameState.value = data.opt("sessionName") as? String
    // With no usable model Pi (1.1.0, still in 1.3.0) reports a placeholder whose provider is "unknown" rather than
    // omitting it.
    timeline.setModel(data.optJSONObject("model")?.takeUnless { it.optString("provider") == "unknown" })
    timeline.setThinkingLevel(data.opt("thinkingLevel") as? String)
  }

  /** Best-effort `get_state` after a change Pi may have adjusted; events and the next change correct a miss. */
  private suspend fun syncState() {
    try {
      applyState(call("get_state"))
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
    const val TAG = "PipkinAgent"
    const val PI_VERSION = "pi_version"
    const val RESYNC_TIMEOUT_MILLIS = 15_000L
    const val AUTO_RECONNECT_INTERVAL_MILLIS = 60_000L
  }
}

/** The `message` and `images` of Pi's `prompt` and `steer`. */
private fun messageParams(message: String, images: List<ImagePart>): JSONObject {
  val params = JSONObject().put("message", message)
  if (images.isEmpty()) return params
  val parts = images.map { JSONObject().put("type", "image").put("data", it.data).put("mimeType", it.mimeType) }
  return params.put("images", JSONArray(parts))
}

/** The Pi `timestamp` of every message in a `get_messages` history. */
private fun messageTimestamps(messages: JSONArray): Set<Long> = (0 until messages.length()).mapNotNullTo(HashSet()) {
  messages.optJSONObject(it)?.optLong("timestamp")?.takeIf { timestamp -> timestamp > 0 }
}
