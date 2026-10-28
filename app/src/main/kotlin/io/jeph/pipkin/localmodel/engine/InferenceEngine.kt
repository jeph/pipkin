package io.jeph.pipkin.localmodel.engine

import org.json.JSONObject

/**
 * One model runtime inside the `:inference` process. It serves the model over OpenAI-compatible HTTP on loopback, the
 * same API whichever runtime it is, so the main process only proxies bytes and never needs to know which one runs.
 * Each `:inference` process starts exactly one engine; switching models restarts the process, which is the only way
 * to be sure Android gets all of the old model's native and GPU memory back.
 */
internal interface InferenceEngine {
  /**
   * Loads [config]'s model and serves `GET /health` and `POST /v1/chat/completions` on 127.0.0.1:[EngineConfig.port],
   * requiring `Authorization: Bearer` [EngineConfig.apiKey]. Returns once the server answers; reports load progress
   * from 0 to 1 through [onProgress]. Throws [EngineException] when the model can't be loaded or served.
   */
  fun start(config: EngineConfig, onProgress: (Float) -> Unit)
}

/** Why an engine couldn't start, in English for the log; the main process shows its own text for [code]. */
internal class EngineException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** What runs a model file, decided by the file's format. */
internal enum class EngineKind(val wire: String) {
  LLAMA("llama"),
  LITERT("litert"),
  ;

  companion object {
    fun of(wire: String): EngineKind = entries.first { it.wire == wire }
  }
}

/** Where a model's math runs. */
internal enum class Backend(val wire: String) {
  CPU("cpu"),
  GPU("gpu"),
  NPU("npu"),
  ;

  companion object {
    fun of(wire: String): Backend = entries.first { it.wire == wire }
  }
}

/** Everything an `:inference` process needs to start its one engine, passed over AIDL as JSON. */
internal data class EngineConfig(
  val engine: EngineKind,
  val modelPath: String,
  /** The catalog id, which the engine reports as the response's `model`. */
  val modelAlias: String,
  val contextSize: Int,
  val threads: Int,
  val backend: Backend,
  val port: Int,
  val apiKey: String,
) {
  fun toJson(): String = JSONObject()
    .put("engine", engine.wire)
    .put("modelPath", modelPath)
    .put("modelAlias", modelAlias)
    .put("contextSize", contextSize)
    .put("threads", threads)
    .put("backend", backend.wire)
    .put("port", port)
    .put("apiKey", apiKey)
    .toString()

  companion object {
    fun fromJson(text: String): EngineConfig {
      val json = JSONObject(text)
      return EngineConfig(
        engine = EngineKind.of(json.getString("engine")),
        modelPath = json.getString("modelPath"),
        modelAlias = json.getString("modelAlias"),
        contextSize = json.getInt("contextSize"),
        threads = json.getInt("threads"),
        backend = Backend.of(json.getString("backend")),
        port = json.getInt("port"),
        apiKey = json.getString("apiKey"),
      )
    }
  }
}
