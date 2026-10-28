package io.jeph.pipkin.localmodel.catalog

import io.jeph.pipkin.localmodel.CatalogModel
import io.jeph.pipkin.localmodel.ModelArtifact
import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineChoiceTest {
  private val plainPhone = DeviceProfile(socModel = "SM8650", hasOpenCl = false)

  private fun artifact(
    engine: EngineKind,
    backend: Backend,
    socModels: Set<String> = emptySet(),
    contextSize: Int = 4096,
  ) = ModelArtifact(
    engine = engine,
    backend = backend,
    url = "https://example.com/${engine.wire}-${backend.wire}-$contextSize",
    sha256 = "0".repeat(64),
    sizeBytes = 1,
    fileName = "${engine.wire}-${backend.wire}-$contextSize",
    contextSize = contextSize,
    socModels = socModels,
  )

  private val npu = artifact(EngineKind.LITERT, Backend.NPU, setOf("Tensor G5"))
  private val gpu = artifact(EngineKind.LITERT, Backend.GPU)
  private val litertCpu = artifact(EngineKind.LITERT, Backend.CPU)
  private val llama = artifact(EngineKind.LLAMA, Backend.CPU)
  private val everything = CatalogModel("m", "M", reasoning = true, maxTokens = 1, listOf(llama, litertCpu, gpu, npu))

  private fun choose(
    model: CatalogModel = everything,
    device: DeviceProfile = plainPhone,
    override: EngineOverride = EngineOverride.AUTOMATIC,
  ) = EngineChoice.choose(model, device, override)

  @Test
  fun anNpuBuildForThisPhonesSocComesFirst() {
    assertEquals(npu, choose(device = DeviceProfile("Tensor G5", hasOpenCl = true)))
  }

  @Test
  fun theSocModelMatchesWhateverItsCase() {
    assertEquals(npu, choose(device = DeviceProfile("TENSOR G5", hasOpenCl = false)))
  }

  @Test
  fun anNpuBuildForAnotherSocIsPassedOver() {
    assertEquals(gpu, choose(device = DeviceProfile("MT6993", hasOpenCl = true)))
  }

  @Test
  fun withoutASocModelBeforeApi31NoNpuBuildIsChosen() {
    assertEquals(gpu, choose(device = DeviceProfile(socModel = null, hasOpenCl = true)))
  }

  @Test
  fun aGpuBuildNeedsOpenCl() {
    assertEquals(gpu, choose(device = plainPhone.copy(hasOpenCl = true)))
    assertEquals(litertCpu, choose(device = plainPhone))
  }

  @Test
  fun llamaCppRunsWhatLiteRtHasNoBuildOf() {
    val llamaOnly = everything.copy(artifacts = listOf(llama))
    assertEquals(llama, choose(model = llamaOnly))
  }

  @Test
  fun llamaCppRunsAModelWhoseOnlyLiteRtBuildNeedsAnotherPhone() {
    assertEquals(llama, choose(model = everything.copy(artifacts = listOf(npu, llama))))
  }

  @Test
  fun anOverridePicksThatEnginesBestBuild() {
    assertEquals(llama, choose(override = EngineOverride.LLAMA, device = DeviceProfile("Tensor G5", true)))
    assertEquals(gpu, choose(override = EngineOverride.LITERT, device = plainPhone.copy(hasOpenCl = true)))
  }

  @Test
  fun anOverrideForAnEngineWithNothingRunnableFallsBackToAutomatic() {
    val llamaOnly = everything.copy(artifacts = listOf(llama))
    assertEquals(llama, choose(model = llamaOnly, override = EngineOverride.LITERT))
  }

  @Test
  fun aModelWithNothingRunnableHasNoChoice() {
    val npuOnly = everything.copy(artifacts = listOf(npu))
    assertNull(choose(model = npuOnly))
    assertNull(choose(model = npuOnly, override = EngineOverride.LITERT))
  }

  @Test
  fun theEnginesOfferedAreThoseWithARunnableBuild() {
    assertEquals(listOf(EngineKind.LITERT, EngineKind.LLAMA), EngineChoice.engines(everything, plainPhone))
    assertEquals(
      listOf(EngineKind.LLAMA),
      EngineChoice.engines(everything.copy(artifacts = listOf(npu, llama)), plainPhone),
    )
  }

  @Test
  fun aBuildThatHoldsPisPromptBeatsFasterOnesThatDont() {
    val roomy = artifact(EngineKind.LLAMA, Backend.CPU, contextSize = EngineChoice.AGENT_CONTEXT)
    val model = everything.copy(artifacts = listOf(npu, gpu, litertCpu, roomy))
    assertEquals(roomy, choose(model = model, device = DeviceProfile("Tensor G5", hasOpenCl = true)))
  }

  @Test
  fun amongBuildsThatHoldPisPromptLiteRtStillComesFirst() {
    val roomyLlama = artifact(EngineKind.LLAMA, Backend.CPU, contextSize = EngineChoice.AGENT_CONTEXT)
    val roomyLiteRt = artifact(EngineKind.LITERT, Backend.CPU, contextSize = EngineChoice.AGENT_CONTEXT)
    assertEquals(roomyLiteRt, choose(model = everything.copy(artifacts = listOf(roomyLlama, roomyLiteRt))))
  }

  @Test
  fun anOverrideIsFollowedIntoABuildTooSmallForPisPrompt() {
    val roomy = artifact(EngineKind.LLAMA, Backend.CPU, contextSize = EngineChoice.AGENT_CONTEXT)
    val model = everything.copy(artifacts = listOf(litertCpu, roomy))
    assertEquals(litertCpu, choose(model = model, override = EngineOverride.LITERT))
  }

  @Test
  fun everyCatalogModelChoosesABuildThatHoldsPisPromptOnAnyPhone() {
    val phones = listOf(DeviceProfile(socModel = "", hasOpenCl = false), DeviceProfile("Tensor G5", hasOpenCl = true))
    for (model in LocalModelCatalog.models) {
      for (phone in phones) {
        val chosen = checkNotNull(EngineChoice.choose(model, phone, EngineOverride.AUTOMATIC))
        assertTrue("${model.id} on ${phone.socModel}", chosen.contextSize >= EngineChoice.AGENT_CONTEXT)
      }
    }
  }

  @Test
  fun choosingLiteRtForGemmaOnAPixel10TakesItsGpuBuildOverTheShortNpuOne() {
    val gemma = checkNotNull(LocalModelCatalog.model("gemma-4-e2b"))
    val chosen = EngineChoice.choose(gemma, DeviceProfile("Tensor G5", hasOpenCl = true), EngineOverride.LITERT)
    assertEquals(Backend.GPU, chosen?.backend)
  }
}
