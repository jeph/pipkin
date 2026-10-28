package io.jeph.pipkin.localmodel.engine.litert

import android.content.Context
import io.jeph.pipkin.localmodel.engine.EngineConfig
import io.jeph.pipkin.localmodel.engine.EngineException
import io.jeph.pipkin.localmodel.engine.InferenceEngine

/** SKELETON: the LiteRT agent replaces this with a LiteRT-LM engine behind LoopbackHttp. */
internal class LiteRtEngine(private val context: Context) : InferenceEngine {
  override fun start(config: EngineConfig, onProgress: (Float) -> Unit) {
    throw EngineException("unavailable", "LiteRT-LM engine not built yet: ${context.packageName}")
  }
}
