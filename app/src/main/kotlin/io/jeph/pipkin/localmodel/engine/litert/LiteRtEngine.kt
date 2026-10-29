package io.jeph.pipkin.localmodel.engine.litert

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend as LiteRtBackend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig as LiteRtEngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerParameters
import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineConfig
import io.jeph.pipkin.localmodel.engine.EngineException
import io.jeph.pipkin.localmodel.engine.InferenceEngine
import io.jeph.pipkin.localmodel.http.LoopbackHttp
import java.io.File
import java.io.IOException

/**
 * Runs a `.litertlm` model with Google's LiteRT-LM on the CPU, the GPU, or a supported NPU. LiteRT-LM is a library
 * with a Kotlin API rather than a server, so [ChatServer] puts the same OpenAI-compatible HTTP API in front of it that
 * llama.cpp's server speaks.
 */
internal class LiteRtEngine(private val context: Context) : InferenceEngine {
  override fun start(config: EngineConfig, onProgress: (Float) -> Unit) {
    val started = SystemClock.elapsedRealtime()
    val model = readModel(config.modelPath)
    // A model compiled for a fixed context can't hold more, whatever the catalog asks for.
    val maxNumTokens = model.maxContextTokens.takeIf { it > 0 }?.coerceAtMost(config.contextSize) ?: config.contextSize
    val engine = loading("Couldn't load ${config.modelPath} on ${config.backend.wire}") {
      @OptIn(ExperimentalApi::class)
      ExperimentalFlags.enableBenchmark = true
      Engine(
        LiteRtEngineConfig(
          modelPath = config.modelPath,
          backend = backend(config),
          maxNumTokens = maxNumTokens,
          cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }.path,
        ),
      ).apply { initialize() }
    }
    Log.i(
      TAG,
      "Loaded ${config.modelAlias} on ${config.backend.wire} in ${SystemClock.elapsedRealtime() - started} ms, " +
        "$maxNumTokens tokens of ${model.maxContextTokens} the model allows",
    )
    val chat = ChatServer(engine, config.apiKey, config.modelAlias, maxNumTokens, model.sampling)
    // The server's accept thread keeps it, the chat server and the engine alive for the rest of the process.
    try {
      LoopbackHttp(config.port, chat::handle)
    } catch (failure: IOException) {
      throw EngineException("port_unavailable", "Couldn't serve on port ${config.port}: ${failure.message}", failure)
    }
  }

  private fun backend(config: EngineConfig): LiteRtBackend = when (config.backend) {
    Backend.CPU -> LiteRtBackend.CPU(threadCount = config.threads.takeIf { it > 0 })

    Backend.GPU -> LiteRtBackend.GPU()

    // LiteRT-LM looks for the NPU vendor's dispatch library here, among the app's own native libraries.
    Backend.NPU -> LiteRtBackend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
  }

  private class ModelLimits(val maxContextTokens: Int, val sampling: SamplerParameters?)

  private fun readModel(path: String): ModelLimits = loading("Couldn't read $path") {
    ModelInfo.from(path).use { info ->
      ModelLimits(info.maxContextTokens(), (info as? ModelInfo.Llm)?.defaultSamplerParams())
    }
  }

  /** Runs [block], turning LiteRT-LM's failures, including a native library that won't load, into [EngineException]. */
  private fun <T> loading(what: String, block: () -> T): T {
    val failure = try {
      return block()
    } catch (failure: LiteRtLmJniException) {
      failure
    } catch (failure: IllegalStateException) {
      failure
    } catch (failure: IllegalArgumentException) {
      failure
    } catch (failure: UnsatisfiedLinkError) {
      failure
    }
    throw EngineException("load_failed", "$what: ${failure.message}", failure)
  }

  private companion object {
    const val TAG = "PipkinLiteRt"
    const val CACHE_DIR = "litert-lm"
  }
}
