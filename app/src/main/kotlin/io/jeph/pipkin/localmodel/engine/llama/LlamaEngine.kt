package io.jeph.pipkin.localmodel.engine.llama

import io.jeph.pipkin.localmodel.engine.EngineConfig
import io.jeph.pipkin.localmodel.engine.EngineException
import io.jeph.pipkin.localmodel.engine.InferenceEngine

/** SKELETON: the native agent replaces this with llama.cpp's server run through JNI. */
internal class LlamaEngine : InferenceEngine {
  override fun start(config: EngineConfig, onProgress: (Float) -> Unit) {
    throw EngineException("unavailable", "llama.cpp engine not built yet")
  }
}
