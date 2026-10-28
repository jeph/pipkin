package io.jeph.pipkin.localmodel.catalog

import io.jeph.pipkin.localmodel.CatalogModel
import io.jeph.pipkin.localmodel.ModelArtifact
import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineKind

/**
 * The models Pipkin offers to run on the phone, each with every build of it Pipkin knows. The files are pinned by
 * SHA-256 rather than taken from whatever Hugging Face serves today, so a repository that changes a file can't swap
 * what runs on the phone, and a download is only kept once it matches.
 */
internal object LocalModelCatalog {
  val models: List<CatalogModel> = listOf(
    CatalogModel(
      id = "qwen3-0.6b",
      name = "Qwen3 0.6B",
      reasoning = true,
      maxTokens = MAX_TOKENS,
      artifacts = listOf(
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.NPU,
          url = huggingFace("litert-community/Qwen3-0.6B", "Qwen3-0.6B.mediatek.mt6993.litertlm"),
          sha256 = "cc87c8a3df6eb680266a385c22fc3e867824495ff676583716ebe77e6ddd6c97",
          sizeBytes = 1_202_913_280,
          fileName = "Qwen3-0.6B.mediatek.mt6993.litertlm",
          contextSize = 4096,
          socModels = setOf(MEDIATEK_MT6993),
        ),
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.CPU,
          url = huggingFace("litert-community/Qwen3-0.6B", "Qwen3-0.6B.litertlm"),
          sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
          sizeBytes = 613_722_264,
          fileName = "Qwen3-0.6B.litertlm",
          contextSize = 4096,
        ),
        ModelArtifact(
          engine = EngineKind.LLAMA,
          backend = Backend.CPU,
          url = huggingFace("Qwen/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q8_0.gguf"),
          sha256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031",
          sizeBytes = 639_446_688,
          fileName = "Qwen3-0.6B-Q8_0.gguf",
          contextSize = 32768,
        ),
      ),
    ),
    CatalogModel(
      id = "qwen3-1.7b",
      name = "Qwen3 1.7B",
      reasoning = true,
      maxTokens = MAX_TOKENS,
      artifacts = listOf(
        ModelArtifact(
          engine = EngineKind.LLAMA,
          backend = Backend.CPU,
          url = huggingFace("Qwen/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q8_0.gguf"),
          sha256 = "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a",
          sizeBytes = 1_834_426_016,
          fileName = "Qwen3-1.7B-Q8_0.gguf",
          contextSize = 32768,
        ),
      ),
    ),
    CatalogModel(
      id = "qwen3-4b",
      name = "Qwen3 4B",
      reasoning = true,
      maxTokens = MAX_TOKENS,
      artifacts = listOf(
        ModelArtifact(
          engine = EngineKind.LLAMA,
          backend = Backend.CPU,
          url = huggingFace("Qwen/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf"),
          sha256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
          sizeBytes = 2_497_280_256,
          fileName = "Qwen3-4B-Q4_K_M.gguf",
          contextSize = 32768,
        ),
      ),
    ),
    CatalogModel(
      id = "gemma-4-e2b",
      name = "Gemma 4 E2B",
      reasoning = true,
      maxTokens = MAX_TOKENS,
      artifacts = listOf(
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.NPU,
          url = huggingFace(GEMMA_4_E2B_LITERT, "gemma-4-E2B-it_Google_Tensor_G5.litertlm"),
          sha256 = "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc",
          sizeBytes = 3_113_545_589,
          fileName = "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
          // An NPU build's context is fixed when it's compiled; the model card benchmarks this one at 4,096.
          contextSize = 4096,
          socModels = setOf(GOOGLE_TENSOR_G5),
        ),
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.GPU,
          url = huggingFace(GEMMA_4_E2B_LITERT, "gemma-4-E2B-it-gpu.litertlm"),
          sha256 = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
          sizeBytes = 2_008_432_640,
          fileName = "gemma-4-E2B-it-gpu.litertlm",
          // The model card: "The model can support up to 32k context length".
          contextSize = 32768,
        ),
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.CPU,
          url = huggingFace(GEMMA_4_E2B_LITERT, "gemma-4-E2B-it.litertlm"),
          sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
          sizeBytes = 2_588_147_712,
          fileName = "gemma-4-E2B-it.litertlm",
          contextSize = 32768,
        ),
        ModelArtifact(
          engine = EngineKind.LLAMA,
          backend = Backend.CPU,
          url = huggingFace("ggml-org/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_0.gguf"),
          sha256 = "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52",
          sizeBytes = 2_841_481_184,
          fileName = "gemma-4-E2B-it-Q4_0.gguf",
          contextSize = 32768,
        ),
      ),
    ),
  )

  fun model(id: String): CatalogModel? = models.firstOrNull { it.id == id }

  /** The name of the model [id], or the id itself for a model the catalog no longer has. */
  fun name(id: String): String = model(id)?.name ?: id

  /** The provider Pi lists the catalog's models under, whose model ids are [CatalogModel.id]. */
  const val PROVIDER_ID = "pipkin-local"
}

/** The most a reply may take, which Pi is told as each model's `maxTokens`. */
private const val MAX_TOKENS = 8192

private const val GEMMA_4_E2B_LITERT = "litert-community/gemma-4-E2B-it-litert-lm"

/** `Build.SOC_MODEL` of MediaTek's Dimensity 9500, the SoC the MT6993 NPU build is compiled for. */
private const val MEDIATEK_MT6993 = "MT6993"

/** `Build.SOC_MODEL` of the Pixel 10's SoC (`ro.soc.model` on a Pixel 10 reads `Tensor G5`). */
private const val GOOGLE_TENSOR_G5 = "Tensor G5"

private fun huggingFace(repository: String, file: String) = "https://huggingface.co/$repository/resolve/main/$file"
