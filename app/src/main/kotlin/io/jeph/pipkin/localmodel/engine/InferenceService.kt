package io.jeph.pipkin.localmodel.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import io.jeph.pipkin.localmodel.engine.litert.LiteRtEngine
import io.jeph.pipkin.localmodel.engine.llama.LlamaEngine
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Hosts one model in the `:inference` process, apart from the UI's, so that Android killing a model under memory
 * pressure leaves the chats on screen, and so that unloading a model is killing this process. The main process binds
 * it with `BIND_IMPORTANT`, which keeps it as important as the main process while that runs in the foreground.
 */
internal class InferenceService : Service() {
  private val started = AtomicBoolean(false)

  private val binder = object : IInferenceProcess.Stub() {
    override fun pid(): Int = Process.myPid()

    override fun start(configJson: String, listener: IInferenceListener) {
      if (!started.compareAndSet(false, true)) {
        send(listener, failed("already_started", "This process already runs a model"))
        return
      }
      Thread({ run(configJson, listener) }, "inference-start").start()
    }
  }

  override fun onBind(intent: Intent): IBinder = binder

  private fun run(configJson: String, listener: IInferenceListener) {
    val config = EngineConfig.fromJson(configJson)
    val engine = when (config.engine) {
      EngineKind.LLAMA -> LlamaEngine()
      EngineKind.LITERT -> LiteRtEngine(applicationContext)
    }
    try {
      engine.start(config) { progress -> send(listener, JSONObject().put("type", "progress").put("value", progress)) }
      send(listener, JSONObject().put("type", "ready"))
    } catch (failure: EngineException) {
      Log.w(TAG, "Engine ${config.engine.wire} failed to start: ${failure.message}", failure)
      send(listener, failed(failure.code, failure.message.orEmpty()))
    }
  }

  private fun failed(code: String, message: String) =
    JSONObject().put("type", "failed").put("code", code).put("message", message)

  private fun send(listener: IInferenceListener, event: JSONObject) {
    try {
      listener.onEvent(event.toString())
    } catch (_: RemoteException) {
      // The main process died; it kills or abandons this process on its own.
    }
  }

  private companion object {
    const val TAG = "PipkinInference"
  }
}
