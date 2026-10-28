package io.jeph.pipkin.localmodel.catalog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledModelsTest {
  private val qwen = checkNotNull(LocalModelCatalog.model("qwen3-0.6b"))
  private val emulator = DeviceProfile(socModel = "", hasOpenCl = false)
  private val dir = File("/models")

  private fun installed(
    files: Set<String>,
    overrides: Map<String, EngineOverride> = emptyMap(),
    device: DeviceProfile = emulator,
  ) = installedModels(listOf(qwen), files, overrides, device, dir)

  @Test
  fun theChosenBuildIsListedWhenDownloaded() {
    val files = setOf("Qwen3-0.6B.litertlm", "Qwen3-0.6B-Q8_0.gguf")
    val listed = installed(files).single()
    assertEquals("Qwen3-0.6B-Q8_0.gguf", listed.artifact.fileName)
    assertEquals(File(dir, "Qwen3-0.6B-Q8_0.gguf"), listed.file)
    val overridden = installed(files, mapOf(qwen.id to EngineOverride.LITERT)).single()
    assertEquals("Qwen3-0.6B.litertlm", overridden.artifact.fileName)
  }

  @Test
  fun anotherDownloadedBuildIsListedWhenTheChosenOneIsnt() {
    assertEquals("Qwen3-0.6B-Q8_0.gguf", installed(setOf("Qwen3-0.6B-Q8_0.gguf")).single().artifact.fileName)
  }

  @Test
  fun anNpuBuildForAnotherPhoneIsntListed() {
    val pixel = DeviceProfile(socModel = "Tensor G5", hasOpenCl = true)
    assertTrue(installed(setOf("Qwen3-0.6B.mediatek.mt6993.litertlm"), device = pixel).isEmpty())
  }

  @Test
  fun aModelWithNothingDownloadedIsntListed() {
    assertTrue(installed(emptySet()).isEmpty())
  }
}
