package io.jeph.pipkin.localmodel

import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineKind
import java.io.File

/**
 * One downloadable file of a [CatalogModel]: the same model built for one engine and backend. [socModels] lists the
 * `Build.SOC_MODEL` values an NPU build was compiled for; it's empty for files that run on any phone.
 */
internal data class ModelArtifact(
  val engine: EngineKind,
  val backend: Backend,
  val url: String,
  val sha256: String,
  val sizeBytes: Long,
  val fileName: String,
  /** The context the engine is started with, which Pi is told as the model's context window. */
  val contextSize: Int,
  val socModels: Set<String> = emptySet(),
)

/** A model Pipkin offers, with every build of it; [id] is its model id under the `pipkin-local` provider in Pi. */
internal data class CatalogModel(
  val id: String,
  val name: String,
  val reasoning: Boolean,
  val maxTokens: Int,
  val artifacts: List<ModelArtifact>,
)

/** A model whose chosen [artifact] is downloaded and verified at [file]. */
internal data class InstalledModel(val model: CatalogModel, val artifact: ModelArtifact, val file: File)

/** What the `:inference` process is doing. */
internal sealed interface RuntimeState {
  data object Stopped : RuntimeState

  /** [progress] is from 0 to 1, or null until the engine reports some: LiteRT-LM never does. */
  data class Loading(val modelId: String, val progress: Float?) : RuntimeState

  data class Ready(val modelId: String, val engine: EngineKind, val backend: Backend, val contextSize: Int) :
    RuntimeState

  data class Failed(val modelId: String, val message: String) : RuntimeState
}

/** Where one Pi session's request to a local model stands, for the chat to show while it waits. */
internal sealed interface SessionActivity {
  /** Behind [position] other requests; [waitingFor] is the model another chat holds, when that's what it waits on. */
  data class Queued(val modelId: String, val waitingFor: String?, val position: Int) : SessionActivity

  /** [progress] is from 0 to 1, or null when unknown, as for [RuntimeState.Loading]. */
  data class Loading(val modelId: String, val progress: Float?) : SessionActivity

  data class ReadingPrompt(val processed: Int, val total: Int, val cached: Int) : SessionActivity

  data class Writing(val tokens: Int, val tokensPerSecond: Float) : SessionActivity
}
