package io.jeph.pipkin.localmodel.catalog

import io.jeph.pipkin.localmodel.engine.Backend
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledModelsTest {
  private val qwen = checkNotNull(LocalModelCatalog.model("qwen3-0.6b"))
  private val emulator = DeviceProfile(socModel = "", gpu = null)
  private val adreno = DeviceProfile(socModel = "SM8850", gpu = "QUALCOMM Adreno(TM) 840")
  private val dir = File("/models")

  private fun installed(files: Set<String>, device: DeviceProfile = emulator) =
    installedModels(listOf(qwen), files, device, dir)

  @Test
  fun theChosenBuildIsListedWhenDownloaded() {
    val listed = installed(setOf("Qwen3-0.6B.litertlm", "Qwen3-0.6B-Q8_0.gguf")).single()
    assertEquals("Qwen3-0.6B-Q8_0.gguf", listed.artifact.fileName)
    assertEquals(File(dir, "Qwen3-0.6B-Q8_0.gguf"), listed.file)
  }

  @Test
  fun oneGgufRunsOnTheGpuOrTheCpu() {
    val gguf = setOf("Qwen3-0.6B-Q8_0.gguf")
    assertEquals(Backend.GPU, installed(gguf, adreno).single().artifact.backend)
    assertEquals(Backend.CPU, installed(gguf).single().artifact.backend)
  }

  @Test
  fun aBuildThatFailedHereGivesWayToAnotherDownloadedOne() {
    val gpu = installed(setOf("Qwen3-0.6B-Q8_0.gguf"), adreno).single().artifact
    val after = installed(setOf("Qwen3-0.6B-Q8_0.gguf"), adreno.copy(failedBuilds = setOf(gpu.kind))).single()
    assertEquals(Backend.CPU, after.artifact.backend)
  }

  @Test
  fun anotherDownloadedBuildIsListedWhenTheChosenOneIsnt() {
    assertEquals("Qwen3-0.6B.litertlm", installed(setOf("Qwen3-0.6B.litertlm")).single().artifact.fileName)
  }

  @Test
  fun anNpuBuildForAnotherPhoneIsntListed() {
    val pixel = DeviceProfile(socModel = "Tensor G5", gpu = null)
    assertTrue(installed(setOf("Qwen3-0.6B.mediatek.mt6993.litertlm"), device = pixel).isEmpty())
  }

  @Test
  fun aModelWithNothingDownloadedIsntListed() {
    assertTrue(installed(emptySet()).isEmpty())
  }
}
