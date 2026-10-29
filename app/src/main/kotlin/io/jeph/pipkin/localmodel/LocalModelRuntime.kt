package io.jeph.pipkin.localmodel

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.host.TermuxBuild
import io.jeph.pipkin.localmodel.catalog.kind
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Serves the downloaded models to Pi: the endpoint Pi's `pipkin-local` provider calls while [serve] is on, the
 * scheduler that runs one request and keeps one model loaded at a time, and the `:inference` process that holds that
 * model. Keeps Pipkin awake while a request waits or runs, unloads the model after the [preferences]' idle minutes
 * without requests, unloads an idle model as soon as Android says Pipkin went to the background, and saves a GPU or
 * NPU build that fails to load in [preferences], so the next load picks another.
 */
internal class LocalModelRuntime(
  context: Context,
  private val scope: CoroutineScope,
  private val keepAlive: KeepAlive,
  private val installed: StateFlow<List<InstalledModel>>,
  preferences: Preferences,
  private val apiKey: () -> String,
) {
  private val process = InferenceProcess(context, installed = { installed.value }) { build ->
    scope.launch { preferences.addLocalModelFailedBuild(build.kind) }
  }
  private val scheduler = LocalModelScheduler(
    scope,
    process,
    leaseMillis = LEASE_MILLIS,
    idleUnloadMillis = { preferences.localModelIdleUnloadMinutes.value * MILLIS_PER_MINUTE },
  )
  private val live = MutableStateFlow<Map<String, SessionActivity>>(emptyMap())
  private var server: LocalModelServer? = null

  /** What the `:inference` process is doing. */
  val state: StateFlow<RuntimeState> = process.state

  /** Each Pi session's request in flight, keyed by Pi's session id. */
  val sessions: StateFlow<Map<String, SessionActivity>> =
    combine(scheduler.queue, process.state, live, ::activities).stateIn(scope, SharingStarted.Eagerly, emptyMap())

  init {
    holdWhileBusy()
    context.applicationContext.registerComponentCallbacks(
      object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
          val idle = scheduler.queue.value.let { it.running == null && it.waiting.isEmpty() }
          if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && idle) process.unload()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
      },
    )
  }

  /** Kills the `:inference` process, failing whatever it was serving. */
  fun unload() = process.unload()

  /** Ends [session]'s hold on the loaded model, as its agent turn has ended. */
  fun endLease(session: String) {
    scope.launch { scheduler.endLease(session) }
  }

  /** Opens the endpoint for Pi, or closes it and unloads the model. */
  fun serve(enabled: Boolean) {
    if (!enabled) {
      server?.close()
      server = null
      process.unload()
      return
    }
    if (server != null) return
    server = try {
      LocalModelServer(
        port = TermuxBuild.LOCAL_MODEL_PORT,
        apiKey = apiKey,
        installed = { installed.value },
        serve = ::run,
        report = ::report,
      )
    } catch (failure: IOException) {
      Log.w(TAG, "Couldn't listen on ${TermuxBuild.LOCAL_MODEL_PORT} for local models", failure)
      null
    }
  }

  private suspend fun run(session: String?, modelId: String, block: suspend (ModelEndpoint) -> Unit) {
    scheduler.run(session, modelId) {
      val endpoint = process.endpoint ?: throw LocalModelException(LocalModelException.Reason.PROCESS_DIED, modelId)
      try {
        block(endpoint)
      } catch (failure: IOException) {
        // A connection the model's process dropped by dying says more as that than as a reset socket.
        if (process.endpoint == null) throw LocalModelException(LocalModelException.Reason.PROCESS_DIED, modelId)
        throw failure
      }
    }
  }

  private fun report(session: String?, activity: SessionActivity?) {
    if (session == null) return
    live.update { if (activity == null) it - session else it + (session to activity) }
  }

  private fun holdWhileBusy() {
    scope.launch {
      var hold: AutoCloseable? = null
      combine(scheduler.queue, process.state) { queue, state ->
        queue.running != null || queue.waiting.isNotEmpty() || state is RuntimeState.Loading
      }.collect { busy ->
        if (busy && hold == null) hold = keepAlive.acquire("local model")
        if (!busy) {
          hold?.close()
          hold = null
        }
      }
    }
  }

  private companion object {
    const val TAG = "PipkinLocalModels"

    /** How long a chat keeps the model after a request, about the length of a tool run between two. */
    const val LEASE_MILLIS = 30_000L
    const val MILLIS_PER_MINUTE = 60_000L

    fun activities(
      queue: QueueSnapshot,
      state: RuntimeState,
      live: Map<String, SessionActivity>,
    ): Map<String, SessionActivity> = buildMap {
      val holder = queue.running?.modelId ?: queue.reservedModel
      queue.waiting.forEachIndexed { index, request ->
        val session = request.session ?: return@forEachIndexed
        put(session, SessionActivity.Queued(request.modelId, holder?.takeIf { it != request.modelId }, index + 1))
      }
      val running = queue.running ?: return@buildMap
      val session = running.session ?: return@buildMap
      val activity = when {
        state is RuntimeState.Loading && state.modelId == running.modelId ->
          SessionActivity.Loading(running.modelId, state.progress)

        else -> live[session] ?: SessionActivity.ReadingPrompt(processed = 0, total = 0, cached = 0)
      }
      put(session, activity)
    }
  }
}
