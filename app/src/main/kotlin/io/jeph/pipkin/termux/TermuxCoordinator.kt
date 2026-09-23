package io.jeph.pipkin.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.jeph.pipkin.agent.AgentService
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Single owner of the pairing store, the daemon connection and the published [state].
 * Auto-connects on every foreground; reattaches without relaunching while the daemon lives.
 */
internal class TermuxCoordinator(private val context: Context) {
  private val store = TermuxStore(context)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Guards every [TermuxStore] call. */
  private val io = Mutex()
  private val lock = Any()
  private val mutable = MutableStateFlow(TermuxState())
  val state: StateFlow<TermuxState> = mutable.asStateFlow()

  // All guarded by `lock`.
  private var epoch = 0L
  private var connection: TermuxConnection? = null
  private var subscription: AutoCloseable? = null
  private var connectJob: Job? = null
  private var idleJob: Job? = null
  private var reconnectJob: Job? = null
  private var launchId: String? = null
  private var launchFailure: CompletableDeferred<RuntimeFailure>? = null
  private var retryDelay = RETRY_MIN
  private var serviceBusy = false
  private val lastSeq = HashMap<Long, Long>()

  fun foreground() {
    synchronized(lock) { idleJob?.cancel() }
    scope.launch {
      when (TermuxUtils.getTermuxInstallationStatus(context)) {
        TermuxInstallationStatus.NOT_INSTALLED -> unavailable(
          RuntimeFailureKind.MissingTermux,
          "Termux is not installed.",
        )

        TermuxInstallationStatus.UNSUPPORTED -> unavailable(
          RuntimeFailureKind.Compatibility,
          "The installed Termux build is not supported.",
        )

        TermuxInstallationStatus.SUPPORTED -> when {
          !TermuxUtils.hasRunCommandPermission(context) ->
            unavailable(RuntimeFailureKind.Permission, "Grant Pipkin the Termux run-command permission above.")

          else -> connect()
        }
      }
    }
  }

  fun background() {
    if (state.value.busy) return
    synchronized(lock) {
      idleJob?.cancel()
      idleJob = scope.launch {
        delay(IDLE_GRACE_MILLIS)
        if (!state.value.busy) disconnect()
      }
    }
  }

  fun consent() {
    scope.launch {
      io.withLock { store.consent(store.pairing()) }
      connect()
    }
  }

  /** Single-flight; a connect already in progress is left alone. */
  fun connect() {
    val myEpoch = synchronized(lock) {
      if (connectJob?.isActive == true) return
      reconnectJob?.cancel()
      ++epoch
    }
    val job = scope.launch {
      try {
        runConnect(myEpoch)
      } catch (error: RuntimeFailure) {
        if (stale(myEpoch)) return@launch
        mutable.update { it.copy(phase = ConnectionPhase.Disconnected, failure = error, progress = null) }
      }
    }
    synchronized(lock) { connectJob = job }
  }

  fun disconnect() {
    teardown()
    mutable.update {
      if (it.phase == ConnectionPhase.Unavailable || it.phase == ConnectionPhase.NeedsConsent) {
        it
      } else {
        it.copy(phase = ConnectionPhase.Disconnected, failure = null, progress = null)
      }
    }
  }

  fun repair() {
    scope.launch {
      disconnect()
      val pairing = io.withLock { store.repair() }
      mutable.update { it.copy(port = pairing.port, failure = null) }
      connect()
    }
  }

  fun shutdownDaemon() {
    scope.launch {
      val live = synchronized(lock) { connection } ?: return@launch
      try {
        mutate(live, "shutdown", JSONObject(), null, 0, SHUTDOWN_TIMEOUT)
      } catch (error: RuntimeFailure) {
        mutable.update { it.copy(failure = error) }
      } catch (error: RuntimeRejection) {
        mutable.update { it.copy(failure = RuntimeFailure(RuntimeFailureKind.Setup, error.reason)) }
      }
      synchronized(lock) {
        launchId?.let(TermuxResultReceiver::unregister)
        launchId = null
      }
      disconnect()
    }
  }

  fun startAgent(workspace: String = "default") {
    scope.launch {
      val live = synchronized(lock) { connection } ?: return@launch
      val generation = maxOf(
        System.currentTimeMillis(),
        (state.value.generations.maxOfOrNull { it.generation } ?: 0L) + 1,
      )
      try {
        val result = mutate(
          live,
          "agent.start",
          JSONObject().put("generation", generation).put("workspace", workspace),
          workspace,
          generation,
          START_TIMEOUT,
        )
        val row = GenerationInfo(
          generation = generation,
          workspace = workspace,
          pid = result.integer("pid"),
          startedAt = System.currentTimeMillis(),
          running = true,
          working = false,
          code = null,
          signal = null,
          lastSeq = 0,
        )
        mutable.update {
          it.copy(generations = it.generations.filter { row -> row.generation != generation } + row, failure = null)
        }
      } catch (error: RuntimeRejection) {
        mutable.update { it.copy(failure = RuntimeFailure(RuntimeFailureKind.Setup, error.reason)) }
      } catch (error: RuntimeFailure) {
        mutable.update { it.copy(failure = error) }
      }
    }
  }

  fun stopAgent(generation: Long) {
    scope.launch {
      val live = synchronized(lock) { connection } ?: return@launch
      try {
        mutate(live, "agent.close", JSONObject().put("generation", generation), null, generation, CLOSE_TIMEOUT)
      } catch (error: RuntimeRejection) {
        mutable.update { it.copy(failure = RuntimeFailure(RuntimeFailureKind.Setup, error.reason)) }
      } catch (error: RuntimeFailure) {
        mutable.update { it.copy(failure = error) }
      }
    }
  }

  fun stopAll() {
    for (row in state.value.generations) if (row.working) stopAgent(row.generation)
  }

  private fun unavailable(kind: RuntimeFailureKind, message: String) {
    teardown()
    mutable.update { it.copy(phase = ConnectionPhase.Unavailable, failure = RuntimeFailure(kind, message)) }
  }

  private fun stale(myEpoch: Long): Boolean = synchronized(lock) { epoch != myEpoch }

  private fun teardown() {
    val (live, listener) = synchronized(lock) {
      epoch++
      reconnectJob?.cancel()
      reconnectJob = null
      val pair = connection to subscription
      connection = null
      subscription = null
      pair
    }
    listener?.close()
    live?.close()
  }

  private suspend fun runConnect(myEpoch: Long) {
    val pairing = io.withLock { store.pairing() }
    if (!pairing.consent) {
      mutable.update { it.copy(phase = ConnectionPhase.NeedsConsent, port = pairing.port) }
      return
    }
    val uid = try {
      context.packageManager.getApplicationInfo(TERMUX_PACKAGE, 0).uid
    } catch (error: PackageManager.NameNotFoundException) {
      throw RuntimeFailure(RuntimeFailureKind.MissingTermux, "Termux is not installed.", error)
    }
    mutable.update { it.copy(phase = ConnectionPhase.Connecting, port = pairing.port, progress = null) }
    val live = try {
      TermuxConnection.open(pairing, uid, HANDSHAKE_TIMEOUT)
    } catch (error: RuntimeFailure) {
      // Only an actual refused TCP connection means absent; TLS/auth failures never launch.
      if (error.kind != RuntimeFailureKind.Endpoint) throw error
      Log.i(TAG, "No supervisor on port ${pairing.port}; launching")
      launchAndPoll(pairing, uid, myEpoch)
    }
    if (stale(myEpoch)) {
      live.close()
      return
    }
    establish(live, myEpoch)
  }

  private suspend fun launchAndPoll(pairing: TermuxPairing, uid: Int, myEpoch: Long): TermuxConnection {
    mutable.update { it.copy(phase = ConnectionPhase.Launching) }
    val id = UUID.randomUUID().toString()
    val failed = CompletableDeferred<RuntimeFailure>()
    synchronized(lock) {
      launchId?.let(TermuxResultReceiver::unregister)
      launchId = id
      launchFailure = failed
    }
    TermuxResultReceiver.register(id) { failure -> daemonExited(id, failure) }
    val callback = PendingIntent.getBroadcast(
      context,
      0,
      Intent(context, TermuxResultReceiver::class.java).setData("pipkin://termux-result/$id".toUri()),
      PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
    )
    try {
      TermuxLauncher.launch(context, pairing, callback)
    } catch (error: RuntimeFailure) {
      TermuxResultReceiver.unregister(id)
      throw error
    }
    val deadline = System.nanoTime() + LAUNCH_TIMEOUT * 1_000_000
    while (System.nanoTime() < deadline) {
      delay(LAUNCH_POLL)
      if (stale(myEpoch)) throw RuntimeFailure(RuntimeFailureKind.Transport, "Connection cancelled.")
      if (failed.isCompleted) throw failed.await()
      try {
        return TermuxConnection.open(pairing, uid, HANDSHAKE_TIMEOUT).also {
          synchronized(lock) { launchFailure = null }
        }
      } catch (error: RuntimeFailure) {
        if (error.kind != RuntimeFailureKind.Endpoint) throw error
      }
    }
    throw RuntimeFailure(
      RuntimeFailureKind.Transport,
      "Termux did not start the supervisor. Open Termux once, check allow-external-apps, then retry.",
    )
  }

  /** Termux reports the command's exit: a launch failure while starting, a daemon exit afterwards. */
  private fun daemonExited(id: String, failure: RuntimeFailure) {
    val (pending, current) = synchronized(lock) {
      if (launchId == id) launchId = null
      launchFailure to epoch
    }
    if (pending != null && !pending.isCompleted) {
      pending.complete(failure)
      return
    }
    Log.i(TAG, "Supervisor exited: ${failure.message}")
    handleFailure(current, failure)
  }

  private suspend fun establish(live: TermuxConnection, myEpoch: Long) {
    synchronized(lock) {
      connection = live
      subscription = live.subscribe(
        onEvent = { handleEvent(myEpoch, it) },
        onFailure = { handleFailure(myEpoch, it) },
      )
    }
    var status = live.request("status", JSONObject(), STATUS_TIMEOUT)
    if (status.opt("piVersion") != PI_VERSION) {
      mutable.update { it.copy(phase = ConnectionPhase.Preparing, progress = null) }
      try {
        mutate(live, "runtime.ensure", JSONObject(), null, 0, ENSURE_TIMEOUT)
      } catch (error: RuntimeRejection) {
        throw RuntimeFailure(RuntimeFailureKind.Prerequisites, error.reason, error)
      }
      status = live.request("status", JSONObject(), STATUS_TIMEOUT)
      if (status.opt("piVersion") != PI_VERSION) {
        throw RuntimeFailure(RuntimeFailureKind.Prerequisites, "Installed Pi version mismatch.")
      }
    }
    val unresolved = io.withLock { store.reconcile(status) }
    val rows = generationsOf(status, state.value.generations)
    if (stale(myEpoch)) return
    synchronized(lock) { retryDelay = RETRY_MIN }
    mutable.update {
      it.copy(
        phase = ConnectionPhase.Connected,
        failure = null,
        progress = null,
        daemon = status,
        generations = rows,
        unresolvedReceipts = unresolved,
      )
    }
    for (row in rows) {
      if (!row.running) continue
      val after = synchronized(lock) { lastSeq[row.generation] ?: 0L }
      val attached = live.request(
        "agent.attach",
        JSONObject().put("generation", row.generation).put("after", after),
        STATUS_TIMEOUT,
      )
      if (attached.boolean("gap")) Log.w(TAG, "Journal gap for generation ${row.generation} after $after")
    }
    syncService()
  }

  private fun generationsOf(status: JSONObject, previous: List<GenerationInfo>): List<GenerationInfo> {
    val values = status.optJSONArray("generations") ?: protocolFailure()
    return List(values.length()) { index ->
      val item = values.optJSONObject(index) ?: protocolFailure()
      val generation = item.integer("generation")
      val running = item.string("state") == "running"
      GenerationInfo(
        generation = generation,
        workspace = item.string("workspace"),
        pid = item.integer("pid"),
        startedAt = item.integer("startedAt"),
        running = running,
        working = running && previous.firstOrNull { it.generation == generation }?.working == true,
        code = (item.opt("code") as? Number)?.toLong(),
        signal = item.opt("signal") as? String,
        lastSeq = item.integer("lastSeq"),
      )
    }
  }

  private fun handleEvent(myEpoch: Long, record: JSONObject) {
    if (stale(myEpoch)) return
    when (record.opt("event")) {
      "progress" -> mutable.update { it.copy(progress = record.string("text").take(4096)) }

      "gap" -> Log.w(
        TAG,
        "Journal gap: ${record.opt("from")}..${record.opt("to")} for generation ${record.opt("generation")}",
      )

      "pi", "stderr", "exit" -> {
        val generation = record.integer("generation")
        val seq = record.integer("seq")
        val fresh = synchronized(lock) {
          if (seq <= (lastSeq[generation] ?: 0L)) return
          lastSeq[generation] = seq
          true
        }
        if (!fresh) return
        mutable.update { current ->
          current.copy(
            generations = current.generations.map { row ->
              if (row.generation != generation) row else applyEvent(row, record, seq)
            },
          )
        }
        syncService()
      }
    }
  }

  private fun applyEvent(row: GenerationInfo, record: JSONObject, seq: Long): GenerationInfo =
    when (record.opt("event")) {
      "pi" -> when (record.optJSONObject("record")?.opt("type")) {
        "agent_start" -> row.copy(working = true, lastSeq = seq)
        "agent_settled" -> row.copy(working = false, lastSeq = seq)
        else -> row.copy(lastSeq = seq)
      }

      "exit" -> row.copy(
        running = false,
        working = false,
        code = (record.opt("code") as? Number)?.toLong(),
        signal = record.opt("signal") as? String,
        lastSeq = seq,
      )

      else -> row.copy(lastSeq = seq)
    }

  private fun handleFailure(myEpoch: Long, failure: RuntimeFailure) {
    if (stale(myEpoch)) return
    teardown()
    mutable.update { it.copy(phase = ConnectionPhase.Disconnected, failure = failure, progress = null) }
    val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    if (!foreground && !state.value.busy) return
    val wait = synchronized(lock) {
      val current = retryDelay
      retryDelay = minOf(retryDelay * 2, RETRY_MAX)
      current
    }
    Log.i(TAG, "Reconnecting in $wait ms: ${failure.message}")
    synchronized(lock) {
      reconnectJob = scope.launch {
        delay(wait)
        connect()
      }
    }
  }

  /** Receipt-tracked request: `pending` before, `accepted`/`rejected` after; timeouts stay uncertain. */
  private suspend fun mutate(
    live: TermuxConnection,
    method: String,
    params: JSONObject,
    session: String?,
    generation: Long,
    timeoutMillis: Long,
  ): JSONObject {
    val id = UUID.randomUUID().toString()
    io.withLock { store.pending(id, method, live.instance, session, generation) }
    try {
      val result = live.request(method, params, timeoutMillis, id)
      io.withLock { store.outcome(id, "accepted") }
      return result
    } catch (error: RuntimeRejection) {
      io.withLock { store.outcome(id, "rejected") }
      throw error
    }
  }

  private fun syncService() {
    val busy = state.value.busy
    val changed = synchronized(lock) {
      if (serviceBusy == busy) return
      serviceBusy = busy
      true
    }
    if (!changed) return
    Log.i(TAG, "AgentService.update($busy)")
    try {
      AgentService.update(context, busy)
    } catch (error: IllegalStateException) {
      // ForegroundServiceStartNotAllowedException on API 31+; keep working without the service.
      Log.w(TAG, "Foreground service refused", error)
      mutable.update {
        it.copy(
          failure = RuntimeFailure(
            RuntimeFailureKind.Setup,
            "Android refused a foreground service; progress will not stream while Pipkin is in the background.",
            error,
          ),
        )
      }
    }
  }

  private companion object {
    const val TAG = "Pipkin"
    const val TERMUX_PACKAGE = "com.termux"
    const val IDLE_GRACE_MILLIS = 30_000L
    const val HANDSHAKE_TIMEOUT = 10_000L
    const val LAUNCH_TIMEOUT = 30_000L
    const val LAUNCH_POLL = 250L
    const val STATUS_TIMEOUT = 30_000L
    const val ENSURE_TIMEOUT = 900_000L
    const val START_TIMEOUT = 30_000L
    const val CLOSE_TIMEOUT = 15_000L
    const val SHUTDOWN_TIMEOUT = 15_000L
    const val RETRY_MIN = 1_000L
    const val RETRY_MAX = 30_000L
  }
}
