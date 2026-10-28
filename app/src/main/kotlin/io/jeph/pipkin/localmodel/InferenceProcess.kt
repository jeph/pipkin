package io.jeph.pipkin.localmodel

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.text.format.Formatter
import android.util.Log
import io.jeph.pipkin.AppStrings
import io.jeph.pipkin.R
import io.jeph.pipkin.localmodel.engine.EngineConfig
import io.jeph.pipkin.localmodel.engine.IInferenceListener
import io.jeph.pipkin.localmodel.engine.IInferenceProcess
import io.jeph.pipkin.localmodel.engine.InferenceService
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
internal class InferenceProcess(context: Context, private val installed: () -> List<InstalledModel>) : ModelHost {
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
    checkMemory(model)
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
    }
    if (result.optString("type") != "ready") {
      val message = result.optString("message")
      unload()
      stateFlow.value = RuntimeState.Failed(modelId, message)
      throw LocalModelException(LocalModelException.Reason.LOAD_FAILED, modelId, message)
    }
    synchronized(lock) { bound.ready = true }
    stateFlow.value = RuntimeState.Ready(modelId, config.engine, config.backend, config.contextSize)
  }

  override fun unload() {
    val bound = synchronized(lock) { current.also { current = null } } ?: return
    bound.stop()
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
    // BIND_IMPORTANT raises the process to the main process's importance, which AgentService keeps in the foreground
    // while chats work, so Android treats the model like the app the user is using.
    val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
    check(context.bindService(intent, bound, flags)) { "Couldn't bind the inference service" }
    return bound
  }

  /** Refuses a model that can't fit beside what's running: loading it would only get some process killed. */
  private fun checkMemory(model: InstalledModel) {
    val memory = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
    val needed = model.file.length() + KV_AND_BUFFERS_BYTES
    val free = memory.availMem - memory.threshold
    if (free < needed) {
      val sizes = AppStrings.get(
        R.string.local_model_error_memory_sizes,
        Formatter.formatShortFileSize(context, needed),
        Formatter.formatShortFileSize(context, free.coerceAtLeast(0)),
      )
      throw LocalModelException(LocalModelException.Reason.NOT_ENOUGH_MEMORY, model.model.id, sizes)
    }
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

    /** A rough allowance for the context cache and working buffers on top of the weights. */
    const val KV_AND_BUFFERS_BYTES = 512L * 1024 * 1024
    const val MEGABYTE_SHIFT = 20
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
) : Exception() {
  enum class Reason { NOT_INSTALLED, NOT_ENOUGH_MEMORY, LOAD_FAILED, PROCESS_DIED }

  /** Read where it lands, so it follows the app's language then. */
  override val message: String
    get() = when (reason) {
      Reason.NOT_INSTALLED -> AppStrings.get(R.string.local_model_error_not_installed, modelId)
      Reason.NOT_ENOUGH_MEMORY -> AppStrings.get(R.string.local_model_error_memory, modelId, detail)
      Reason.LOAD_FAILED -> AppStrings.get(R.string.local_model_error_load_failed, modelId, detail)
      Reason.PROCESS_DIED -> AppStrings.get(R.string.local_model_error_stopped, modelId)
    }
}
