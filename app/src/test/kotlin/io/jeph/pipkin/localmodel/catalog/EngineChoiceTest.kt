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
  private val plainPhone = DeviceProfile(socModel = "SM8650", gpu = null)
  private val adreno = plainPhone.copy(gpu = "QUALCOMM Adreno(TM) 750")
  private val mali = plainPhone.copy(gpu = "Mali-G925-Immortalis MC12")
  private val pixel = DeviceProfile(socModel = "Tensor G5", gpu = "PowerVR D-Series DXT-48-1536")

  private fun artifact(
    engine: EngineKind,
    backend: Backend,
    socModels: Set<String> = emptySet(),
    contextSize: Int = EngineChoice.AGENT_CONTEXT,
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
  private val litertGpu = artifact(EngineKind.LITERT, Backend.GPU)
  private val litertCpu = artifact(EngineKind.LITERT, Backend.CPU)
  private val llamaGpu = artifact(EngineKind.LLAMA, Backend.GPU)
  private val llamaCpu = artifact(EngineKind.LLAMA, Backend.CPU)
  private val everything = CatalogModel(
    "m",
    "M",
    reasoning = true,
    maxTokens = 1,
    listOf(llamaCpu, litertCpu, llamaGpu, litertGpu, npu),
  )

  private fun choose(model: CatalogModel = everything, device: DeviceProfile = plainPhone) =
    EngineChoice.choose(model, device)

  @Test
  fun anNpuBuildForThisPhonesSocComesFirst() {
    assertEquals(npu, choose(device = pixel))
  }

  @Test
  fun theSocModelMatchesWhateverItsCase() {
    assertEquals(npu, choose(device = DeviceProfile("TENSOR G5", gpu = null)))
  }

  @Test
  fun anNpuBuildForAnotherSocOrWithoutASocModelIsPassedOver() {
    assertEquals(litertGpu, choose(device = adreno.copy(socModel = "MT6993")))
    assertEquals(litertGpu, choose(device = adreno.copy(socModel = null)))
  }

  @Test
  fun theGpuBeatsTheCpuAndLiteRtComesFirstOnIt() {
    assertEquals(litertGpu, choose(device = adreno))
    assertEquals(llamaGpu, choose(model = everything.copy(artifacts = listOf(llamaCpu, litertCpu, llamaGpu)), adreno))
  }

  @Test
  fun llamaCppRunsOnAdrenoGpusOnly() {
    val llamaOnly = everything.copy(artifacts = listOf(llamaCpu, llamaGpu))
    assertEquals(llamaGpu, choose(llamaOnly, adreno))
    assertEquals(llamaCpu, choose(llamaOnly, mali))
    assertEquals(litertGpu, choose(device = mali))
  }

  @Test
  fun withoutAnOpenClDriverOnlyTheCpuRuns() {
    assertEquals(litertCpu, choose())
    assertEquals(listOf(litertCpu, llamaCpu), EngineChoice.runnable(everything, plainPhone))
  }

  @Test
  fun aBuildThatFailedHereIsPassedOverForTheNextBest() {
    assertEquals(litertGpu, choose(device = pixel.copy(failedBuilds = setOf(npu.kind))))
    assertEquals(llamaGpu, choose(device = adreno.copy(failedBuilds = setOf(litertGpu.kind))))
  }

  @Test
  fun aModelWithNothingRunnableHasNoChoice() {
    assertNull(choose(model = everything.copy(artifacts = listOf(npu))))
  }

  @Test
  fun aBuildThatHoldsPisPromptBeatsFasterOnesThatDont() {
    val shortNpu = artifact(EngineKind.LITERT, Backend.NPU, setOf("Tensor G5"), contextSize = 4096)
    val shortGpu = artifact(EngineKind.LITERT, Backend.GPU, contextSize = 4096)
    val model = everything.copy(artifacts = listOf(shortNpu, shortGpu, llamaCpu))
    assertEquals(llamaCpu, choose(model, pixel))
  }

  @Test
  fun withNothingThatHoldsPisPromptTheBestBuildRunsAnyway() {
    val shortNpu = artifact(EngineKind.LITERT, Backend.NPU, setOf("Tensor G5"), contextSize = 4096)
    val shortCpu = artifact(EngineKind.LITERT, Backend.CPU, contextSize = 4096)
    assertEquals(shortNpu, choose(everything.copy(artifacts = listOf(shortCpu, shortNpu)), pixel))
  }

  @Test
  fun everyCatalogModelChoosesABuildThatHoldsPisPromptOnAnyPhone() {
    val phones = listOf(DeviceProfile(socModel = "", gpu = null), pixel, adreno, mali)
    for (model in LocalModelCatalog.models) {
      for (phone in phones) {
        val chosen = checkNotNull(EngineChoice.choose(model, phone))
        assertTrue("${model.id} on ${phone.gpu}", chosen.contextSize >= EngineChoice.AGENT_CONTEXT)
      }
    }
  }

  @Test
  fun everyCatalogModelRunsOnAnAdrenoGpu() {
    for (model in LocalModelCatalog.models) {
      assertEquals(model.id, Backend.GPU, EngineChoice.choose(model, adreno)?.backend)
    }
  }
}
