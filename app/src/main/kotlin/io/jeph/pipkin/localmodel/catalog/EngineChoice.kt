package io.jeph.pipkin.localmodel.catalog

import android.os.Build
import io.jeph.pipkin.localmodel.CatalogModel
import io.jeph.pipkin.localmodel.ModelArtifact
import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineKind
import java.io.File

/** Which engine the user wants a model run with; [AUTOMATIC] leaves it to [EngineChoice]. Saved by name. */
internal enum class EngineOverride(val engine: EngineKind?) {
  AUTOMATIC(null),
  LLAMA(EngineKind.LLAMA),
  LITERT(EngineKind.LITERT),
}

/**
 * What [EngineChoice] needs to know about the phone, gathered once so the choice itself is a pure function the tests
 * can run for any phone. [socModel] is `Build.SOC_MODEL`, which Android reports from API 31; [hasOpenCl] says whether
 * the vendor ships an OpenCL driver, which LiteRT-LM's GPU backend runs on.
 */
internal data class DeviceProfile(val socModel: String?, val hasOpenCl: Boolean) {
  companion object {
    fun current(): DeviceProfile = DeviceProfile(
      socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
      hasOpenCl = OPEN_CL_PATHS.any { File(it).exists() },
    )

    private val OPEN_CL_PATHS = listOf("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so")
  }
}

/**
 * Picks the build of a model to run on this phone. LiteRT-LM comes first, since it can run a model on the phone's NPU
 * or GPU and its CPU builds are made for phones; llama.cpp is the fallback for models LiteRT-LM has no build of.
 * Within an engine, an NPU build compiled for this phone's SoC beats a GPU build, which needs an OpenCL driver, which
 * beats a CPU build.
 */
internal object EngineChoice {
  /**
   * The build to run, or null when no build of [model] runs on [device]. An [override] whose engine has no build that
   * runs here, such as one set on another phone, is passed over as if it were [EngineOverride.AUTOMATIC].
   */
  fun choose(model: CatalogModel, device: DeviceProfile, override: EngineOverride): ModelArtifact? {
    val runnable = runnable(model, device)
    return runnable.firstOrNull { it.engine == override.engine } ?: runnable.firstOrNull()
  }

  /** The builds of [model] that can run on [device], best first. */
  fun runnable(model: CatalogModel, device: DeviceProfile): List<ModelArtifact> = model.artifacts
    .filter { artifact ->
      when (artifact.backend) {
        Backend.NPU -> device.socModel != null && artifact.socModels.any { it.equals(device.socModel, true) }
        Backend.GPU -> device.hasOpenCl
        Backend.CPU -> true
      }
    }.sortedWith(compareBy({ ENGINE_ORDER.indexOf(it.engine) }, { BACKEND_ORDER.indexOf(it.backend) }))

  /** The engines [model] has a build of on [device], the choices the user may override [choose] with. */
  fun engines(model: CatalogModel, device: DeviceProfile): List<EngineKind> =
    runnable(model, device).map { it.engine }.distinct()

  private val ENGINE_ORDER = listOf(EngineKind.LITERT, EngineKind.LLAMA)
  private val BACKEND_ORDER = listOf(Backend.NPU, Backend.GPU, Backend.CPU)
}
