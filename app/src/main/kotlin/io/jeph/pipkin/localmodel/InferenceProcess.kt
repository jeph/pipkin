package io.jeph.pipkin.localmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.jeph.pipkin.AppStrings
import io.jeph.pipkin.R
import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineConfig
import io.jeph.pipkin.localmodel.engine.IInferenceListener
import io.jeph.pipkin.localmodel.engine.IInferenceProcess
import io.jeph.pipkin.localmodel.engine.InferenceService
import io.jeph.pipkin.ui.settings.label
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Where a loaded model serves, for the proxy: loopback [port], `Authorization: Bearer` [apiKey]. [modelPath] is the
 * file it loaded, which tells whether it's still the build to serve.
 */
internal data class ModelEndpoint(val modelId: String, val modelPath: String, val port: Int, val apiKey: String)

/**
 * The `:inference` process as a [ModelHost]: loading binds [InferenceService] in a fresh process and starts one engine
 * there; unloading kills the process. Android may also kill it under memory pressure, without warning, which shows here
 * as the binder dying: the state becomes [RuntimeState.Failed] and the next request loads the model again.
 */
internal class InferenceProcess(
  context: Context,
  private val installed: () -> List<InstalledModel>,
  /** Told of a GPU or NPU build that failed to load, so it isn't chosen again. */
  private val onBuildFailed: (ModelArtifact) -> Unit,
) : ModelHost {
  private val context = context.applicationContext
  private val stateFlow = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
  private val lock = Any()
  private var current: Bound? = null

  val state: StateFlow<RuntimeState> = stateFlow.asStateFlow()

  /** The loaded model's endpoint, or null when none serves. */
  val endpoint: ModelEndpoint? get() = synchronized(lock) { current?.takeIf { it.ready }?.endpoint }

  /**
   * The loaded model, as long as it's still the build [installed] lists for it: choosing another engine for a loaded
   * model, or deleting it, makes the next request load what's there now.
   */
  override val loadedModel: String?
    get() {
      val endpoint = endpoint ?: return null
      return endpoint.modelId.takeIf { installed().any { model -> model.file.absolutePath == endpoint.modelPath } }
    }

  override suspend fun load(modelId: String) {
    unloadAndWait()
    val model = installed().firstOrNull { it.model.id == modelId }
      ?: throw LocalModelException(LocalModelException.Reason.NOT_INSTALLED, modelId)
    stateFlow.value = RuntimeState.Loading(modelId, 0f)
    val config = EngineConfig(
      engine = model.artifact.engine,
      modelPath = model.file.absolutePath,
      modelAlias = modelId,
      contextSize = model.artifact.contextSize,
      threads = performanceCores(),
      backend = model.artifact.backend,
      port = freePort(),
      apiKey = randomKey(),
    )
    val bound = bind(config)
    val listener = object : IInferenceListener.Stub() {
      override fun onEvent(eventJson: String) {
        val event = JSONObject(eventJson)
        when (event.optString("type")) {
          "progress" -> stateFlow.value = RuntimeState.Loading(modelId, event.optDouble("value").toFloat())
          else -> bound.outcome.complete(event)
        }
      }
    }
    val result = try {
      bound.service.await().start(config.toJson(), listener)
      bound.outcome.await()
    } catch (cancelled: CancellationException) {
      // Nobody waits for this model any more; a half-loaded process would hold its memory and show as loading.
      unload()
      throw cancelled
    } catch (died: LocalModelException) {
      // A GPU or NPU driver that crashes takes the process with it, every time, so that's the build failing too.
      if (config.backend == Backend.CPU) throw died
      failed(model, AppStrings.get(R.string.local_model_error_crashed))
    }
    if (result.optString("type") != "ready") failed(model, result.optString("message"))
    synchronized(lock) { bound.ready = true }
    stateFlow.value = RuntimeState.Ready(modelId, config.engine, config.backend, config.contextSize)
  }

  /**
   * Gives up on [model]'s load, which failed for [reason]. A GPU or NPU build is reported to [onBuildFailed], so the
   * next load picks another build; the CPU is the last resort, so a CPU build stays.
   */
  private fun failed(model: InstalledModel, reason: String): Nothing {
    val modelId = model.model.id
    unload()
    stateFlow.value = RuntimeState.Failed(modelId, reason)
    val backend = model.artifact.backend
    if (backend == Backend.CPU) throw LocalModelException(LocalModelException.Reason.LOAD_FAILED, modelId, reason)
    onBuildFailed(model.artifact)
    throw LocalModelException(LocalModelException.Reason.BUILD_FAILED, modelId, reason, model.artifact)
  }

  /** Kills the process, if any, and clears what the status shows, including why the last load failed. */
  override fun unload() {
    val bound = synchronized(lock) { current.also { current = null } }
    bound?.stop()
    stateFlow.value = RuntimeState.Stopped
  }

  /** Unloads, then waits for the old process to be gone, so binding again starts a new one. */
  private suspend fun unloadAndWait() {
    val bound = synchronized(lock) { current } ?: return
    unload()
    withTimeoutOrNull(DEATH_TIMEOUT_MILLIS) { bound.dead.await() }
  }

  private fun bind(config: EngineConfig): Bound {
    val bound = Bound(ModelEndpoint(config.modelAlias, config.modelPath, config.port, config.apiKey))
    synchronized(lock) { current = bound }
    val intent = Intent(context, InferenceService::class.java)
    // BIND_NOT_PERCEPTIBLE ranks the process just below perceptible ones such as Termux's foreground service, and far
    // above background apps: under memory pressure Android kills those first, then the model, and only then Termux,
    // which runs Pi. It keeps the scheduling of the main process, which AgentService keeps in the foreground.
    val flags = Context.BIND_AUTO_CREATE or Context.BIND_NOT_PERCEPTIBLE
    check(context.bindService(intent, bound, flags)) { "Couldn't bind the inference service" }
    return bound
  }

  private inner class Bound(val endpoint: ModelEndpoint) : ServiceConnection {
    val service = CompletableDeferred<IInferenceProcess>()

    /** The engine's `ready` or `failed` event; a process that dies before either fails it. */
    val outcome = CompletableDeferred<JSONObject>()
    val dead = CompletableDeferred<Unit>()
    var ready = false
    private var pid = 0
    private var isBound = true

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      val process = IInferenceProcess.Stub.asInterface(binder)
      pid = process.pid()
      binder.linkToDeath({ died() }, 0)
      service.complete(process)
    }

    override fun onServiceDisconnected(name: ComponentName) = died()

    override fun onBindingDied(name: ComponentName) = died()

    fun stop() {
      unbind()
      if (pid != 0) Process.killProcess(pid)
    }

    private fun unbind() {
      val wasBound = synchronized(lock) { isBound.also { isBound = false } }
      if (wasBound) context.unbindService(this)
    }

    private fun died() {
      if (!dead.complete(Unit)) return
      val failure = LocalModelException(LocalModelException.Reason.PROCESS_DIED, endpoint.modelId)
      service.completeExceptionally(failure)
      outcome.completeExceptionally(failure)
      val wasCurrent = synchronized(lock) { (current === this).also { if (it) current = null } }
      if (!wasCurrent) return
      Log.w(TAG, "The inference process for ${endpoint.modelId} died")
      unbind()
      stateFlow.value = RuntimeState.Failed(endpoint.modelId, failure.message)
    }
  }

  private companion object {
    const val TAG = "PipkinLocalModels"
    const val DEATH_TIMEOUT_MILLIS = 5_000L
    const val KEY_BYTES = 24
    const val FAST_CORE_SHARE = 0.7

    fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    fun randomKey(): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes))

    /**
     * The cores whose top clock is near the fastest one's. llama.cpp runs at the pace of its slowest thread, so the
     * efficiency cores only slow it down, and leaving them free keeps the UI smooth.
     */
    fun performanceCores(): Int {
      val clocks = File("/sys/devices/system/cpu").listFiles { file -> file.name.matches(Regex("cpu\\d+")) }
        .orEmpty()
        .mapNotNull { File(it, "cpufreq/cpuinfo_max_freq").takeIf(File::canRead)?.readText()?.trim()?.toLongOrNull() }
      val fastest = clocks.maxOrNull() ?: return (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
      return clocks.count { it >= fastest * FAST_CORE_SHARE }.coerceAtLeast(1)
    }
  }
}

/** A local model request that couldn't be served, for the reason the chat shows. */
internal class LocalModelException(
  private val reason: Reason,
  private val modelId: String,
  private val detail: String = "",
  /** The build that failed, for [Reason.BUILD_FAILED]. */
  private val build: ModelArtifact? = null,
) : Exception() {
  enum class Reason { NOT_INSTALLED, LOAD_FAILED, BUILD_FAILED, PROCESS_DIED }

  /** Read where it lands, so it follows the app's language then. */
  override val message: String
    get() = when (reason) {
      Reason.NOT_INSTALLED -> AppStrings.get(R.string.local_model_error_not_installed, modelId)

      Reason.LOAD_FAILED -> AppStrings.get(R.string.local_model_error_load_failed, modelId, detail)

      Reason.BUILD_FAILED -> AppStrings.get(
        R.string.local_model_error_build_failed,
        modelId,
        AppStrings.get(checkNotNull(build).engine.label),
        AppStrings.get(build.backend.label),
        detail,
      )

      Reason.PROCESS_DIED -> AppStrings.get(R.string.local_model_error_stopped, modelId)
    }
}
