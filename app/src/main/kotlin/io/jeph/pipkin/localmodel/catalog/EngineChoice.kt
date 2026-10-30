package io.jeph.pipkin.localmodel.catalog

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.localmodel.CatalogModel
import io.jeph.pipkin.localmodel.ModelArtifact
import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineKind
import io.jeph.pipkin.localmodel.engine.OpenClProbe

/**
 * What [EngineChoice] needs to know about the phone, gathered once so the choice itself is a pure function the tests
 * can run for any phone. [socModel] is `Build.SOC_MODEL`, which Android reports from API 31. [gpu] is the name the
 * vendor's OpenCL driver gives the GPU, or null when Pipkin can't load one; both engines' GPU backends run on OpenCL.
 * [memoryBytes] is the phone's total memory as Android reports it. [failedBuilds] are the [ModelArtifact.kind]s that
 * failed to load a model here, which aren't chosen again.
 */
internal data class DeviceProfile(
  val socModel: String?,
  val gpu: String?,
  val memoryBytes: Long,
  val failedBuilds: Set<String> = emptySet(),
) {
  /** Whether this phone can run [artifact]'s engine on its backend, as far as Pipkin can tell before loading it. */
  fun runs(artifact: ModelArtifact): Boolean {
    if (artifact.kind in failedBuilds) return false
    return when (artifact.backend) {
      Backend.NPU -> socModel != null && artifact.socModels.any { it.equals(socModel, true) }

      // llama.cpp's OpenCL backend has kernels for Qualcomm's Adreno GPUs only.
      Backend.GPU -> gpu != null && (artifact.engine == EngineKind.LITERT || gpu.contains(ADRENO, true))

      Backend.CPU -> true
    }
  }

  companion object {
    /**
     * This phone, with the [gpu] OpenCL named when the [preferences] last asked under this Android build, else asking
     * the driver now, which loads it, so call this off the main thread.
     */
    fun current(context: Context, preferences: Preferences): DeviceProfile {
      val gpu = preferences.localModelGpu() ?: OpenClProbe.gpuName().orEmpty().also(preferences::setLocalModelGpu)
      val memory = ActivityManager.MemoryInfo()
      context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
      return DeviceProfile(
        socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
        gpu = gpu.ifEmpty { null },
        memoryBytes = memory.totalMem,
      )
    }

    private const val ADRENO = "Adreno"
  }
}

/** The engine and backend of a build, such as `litert/gpu`, which is what can fail to load on a phone. */
internal val ModelArtifact.kind: String get() = "${engine.wire}/${backend.wire}"

/**
 * Picks the build of a model to run on this phone, and the context to run it with. A build has to hold a coding agent's
 * conversation first: Pi's system prompt and tools alone come to about 7,000 tokens, so a build that can't start with
 * [AGENT_CONTEXT] is chosen only when no build can. Among builds that fit, the NPU beats the GPU, which beats the CPU,
 * the last resort; on the same chip LiteRT-LM comes first, since its builds are made for phones.
 */
internal object EngineChoice {
  /** The build to run, or null when no build of [model] runs on [device]. */
  fun choose(model: CatalogModel, device: DeviceProfile): ModelArtifact? {
    val runnable = runnable(model, device)
    return runnable.firstOrNull { it.contextLimit >= AGENT_CONTEXT } ?: runnable.firstOrNull()
  }

  /** The builds of [model] that can run on [device], best first, before telling apart those too small for Pi. */
  fun runnable(model: CatalogModel, device: DeviceProfile): List<ModelArtifact> = model.artifacts
    .filter(device::runs)
    .sortedWith(compareBy({ BACKEND_ORDER.indexOf(it.backend) }, { ENGINE_ORDER.indexOf(it.engine) }))

  /**
   * The context to start [artifact] of [model] with on [device]: the build's whole [ModelArtifact.contextLimit] when
   * the model and its cache for that much take at most a [MEMORY_SHARE] of the phone's memory, else [AGENT_CONTEXT].
   * The engines hold the whole context's cache in memory from the start, and the bigger the model's footprint, the
   * sooner Android stops other apps, then the model, to free memory.
   */
  fun contextSize(model: CatalogModel, artifact: ModelArtifact, device: DeviceProfile): Int {
    if (artifact.contextLimit <= AGENT_CONTEXT) return artifact.contextLimit
    val needed = artifact.sizeBytes + cacheBytesPerToken(model, artifact) * artifact.contextLimit
    return if (needed <= device.memoryBytes / MEMORY_SHARE) artifact.contextLimit else AGENT_CONTEXT
  }

  /** llama.cpp's CPU cache is 8-bit, `q8_0`: 34 bytes per 32 values, where 16 bits take 64. */
  private fun cacheBytesPerToken(model: CatalogModel, artifact: ModelArtifact): Long =
    if (artifact.engine == EngineKind.LLAMA && artifact.backend == Backend.CPU) {
      model.cacheBytesPerToken * Q8_0_BLOCK_BYTES / F16_BLOCK_BYTES
    } else {
      model.cacheBytesPerToken
    }

  /** The context Pi needs: its system prompt and tools, about 7,000 tokens, and room for the conversation. */
  const val AGENT_CONTEXT = 16_384

  /** The context a build that isn't compiled for a fixed one gets on a phone with the memory for it. */
  const val LONG_CONTEXT = 32_768

  /** A model may take at most half the phone's memory, leaving the rest to Android, Termux and the other apps. */
  private const val MEMORY_SHARE = 2
  private const val Q8_0_BLOCK_BYTES = 34
  private const val F16_BLOCK_BYTES = 64

  private val BACKEND_ORDER = listOf(Backend.NPU, Backend.GPU, Backend.CPU)
  private val ENGINE_ORDER = listOf(EngineKind.LITERT, EngineKind.LLAMA)
}
