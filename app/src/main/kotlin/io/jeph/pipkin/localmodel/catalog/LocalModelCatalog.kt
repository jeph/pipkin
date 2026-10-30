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
      cacheBytesPerToken = attentionCache(layers = 28, kvHeads = 8, headDim = 128),
      artifacts = listOf(
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.NPU,
          url = huggingFace("litert-community/Qwen3-0.6B", "Qwen3-0.6B.mediatek.mt6993.litertlm"),
          sha256 = "cc87c8a3df6eb680266a385c22fc3e867824495ff676583716ebe77e6ddd6c97",
          sizeBytes = 1_202_913_280,
          fileName = "Qwen3-0.6B.mediatek.mt6993.litertlm",
          contextLimit = 4096,
          socModels = setOf(MEDIATEK_MT6993),
        ),
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.CPU,
          url = huggingFace("litert-community/Qwen3-0.6B", "Qwen3-0.6B.litertlm"),
          sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
          sizeBytes = 613_722_264,
          fileName = "Qwen3-0.6B.litertlm",
          contextLimit = 4096,
        ),
      ) + llamaBuilds(
        repository = "Qwen/Qwen3-0.6B-GGUF",
        file = "Qwen3-0.6B-Q8_0.gguf",
        sha256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031",
        sizeBytes = 639_446_688,
      ),
    ),
    CatalogModel(
      id = "qwen3-1.7b",
      name = "Qwen3 1.7B",
      reasoning = true,
      cacheBytesPerToken = attentionCache(layers = 28, kvHeads = 8, headDim = 128),
      artifacts = llamaBuilds(
        repository = "Qwen/Qwen3-1.7B-GGUF",
        file = "Qwen3-1.7B-Q8_0.gguf",
        sha256 = "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a",
        sizeBytes = 1_834_426_016,
      ),
    ),
    CatalogModel(
      id = "qwen3-4b",
      name = "Qwen3 4B",
      reasoning = true,
      cacheBytesPerToken = attentionCache(layers = 36, kvHeads = 8, headDim = 128),
      artifacts = llamaBuilds(
        repository = "Qwen/Qwen3-4B-GGUF",
        file = "Qwen3-4B-Q4_K_M.gguf",
        sha256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
        sizeBytes = 2_497_280_256,
      ),
    ),
    CatalogModel(
      id = "qwen3-8b",
      name = "Qwen3 8B",
      reasoning = true,
      cacheBytesPerToken = attentionCache(layers = 36, kvHeads = 8, headDim = 128),
      artifacts = llamaBuilds(
        repository = "Qwen/Qwen3-8B-GGUF",
        file = "Qwen3-8B-Q4_K_M.gguf",
        sha256 = "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
        sizeBytes = 5_027_783_488,
      ),
    ),
    // Qwen3.5's LiteRT-LM builds keep 4,096 tokens, too few for Pi's prompt, so it runs on llama.cpp from Unsloth's
    // GGUFs. Most of its layers keep a fixed-size state rather than a cache that grows with the conversation.
    CatalogModel(
      id = "qwen3.5-2b",
      name = "Qwen3.5 2B",
      reasoning = true,
      cacheBytesPerToken = attentionCache(layers = 6, kvHeads = 2, headDim = 256),
      artifacts = llamaBuilds(
        repository = "unsloth/Qwen3.5-2B-GGUF",
        file = "Qwen3.5-2B-Q4_K_M.gguf",
        sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
        sizeBytes = 1_280_835_840,
      ),
    ),
    CatalogModel(
      id = "qwen3.5-4b",
      name = "Qwen3.5 4B",
      reasoning = true,
      cacheBytesPerToken = attentionCache(layers = 8, kvHeads = 4, headDim = 256),
      artifacts = llamaBuilds(
        repository = "unsloth/Qwen3.5-4B-GGUF",
        file = "Qwen3.5-4B-Q4_K_M.gguf",
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        sizeBytes = 2_740_937_888,
      ),
    ),
    CatalogModel(
      id = "gemma-4-e2b",
      name = "Gemma 4 E2B",
      reasoning = true,
      cacheBytesPerToken = attentionCache(layers = 7, kvHeads = 1, headDim = 512),
      artifacts = listOf(
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.NPU,
          url = huggingFace(GEMMA_4_E2B_LITERT, "gemma-4-E2B-it_Google_Tensor_G5.litertlm"),
          sha256 = "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc",
          sizeBytes = 3_113_545_589,
          fileName = "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
          // An NPU build's context is fixed when it's compiled; the model card benchmarks this one at 4,096.
          contextLimit = 4096,
          socModels = setOf(GOOGLE_TENSOR_G5),
        ),
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.GPU,
          url = huggingFace(GEMMA_4_E2B_LITERT, "gemma-4-E2B-it-gpu.litertlm"),
          sha256 = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
          sizeBytes = 2_008_432_640,
          fileName = "gemma-4-E2B-it-gpu.litertlm",
          contextLimit = EngineChoice.LONG_CONTEXT,
        ),
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.CPU,
          url = huggingFace(GEMMA_4_E2B_LITERT, "gemma-4-E2B-it.litertlm"),
          sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
          sizeBytes = 2_588_147_712,
          fileName = "gemma-4-E2B-it.litertlm",
          contextLimit = EngineChoice.LONG_CONTEXT,
        ),
      ) + llamaBuilds(
        repository = "ggml-org/gemma-4-E2B-it-GGUF",
        file = "gemma-4-E2B-it-Q4_0.gguf",
        sha256 = "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52",
        sizeBytes = 2_841_481_184,
      ),
    ),
    // LiteRT-LM's builds only: its GGUF is about 4.8 GB, and as llama.cpp's GPU build comes before LiteRT-LM's CPU one,
    // a phone whose GPU can't run LiteRT-LM's would download it rather than the 3.7 GB CPU build.
    CatalogModel(
      id = "gemma-4-e4b",
      name = "Gemma 4 E4B",
      reasoning = true,
      cacheBytesPerToken = attentionCache(layers = 7, kvHeads = 2, headDim = 512),
      artifacts = listOf(
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.GPU,
          url = huggingFace(GEMMA_4_E4B_LITERT, "gemma-4-E4B-it-gpu.litertlm"),
          sha256 = "4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff",
          sizeBytes = 2_969_059_328,
          fileName = "gemma-4-E4B-it-gpu.litertlm",
          contextLimit = EngineChoice.LONG_CONTEXT,
        ),
        ModelArtifact(
          engine = EngineKind.LITERT,
          backend = Backend.CPU,
          url = huggingFace(GEMMA_4_E4B_LITERT, "gemma-4-E4B-it.litertlm"),
          sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
          sizeBytes = 3_659_530_240,
          fileName = "gemma-4-E4B-it.litertlm",
          contextLimit = EngineChoice.LONG_CONTEXT,
        ),
      ),
    ),
    // Mistral's own GGUF. Its LiteRT-LM build keeps 4,096 tokens, too few for Pi's prompt.
    CatalogModel(
      id = "ministral-3-3b",
      name = "Ministral 3 3B",
      reasoning = false,
      cacheBytesPerToken = attentionCache(layers = 26, kvHeads = 8, headDim = 128),
      artifacts = llamaBuilds(
        repository = "mistralai/Ministral-3-3B-Instruct-2512-GGUF",
        file = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
        sha256 = "9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8",
        sizeBytes = 2_147_023_008,
      ),
    ),
  )

  fun model(id: String): CatalogModel? = models.firstOrNull { it.id == id }

  /** The name of the model [id], or the id itself for a model the catalog no longer has. */
  fun name(id: String): String = model(id)?.name ?: id

  /** The provider Pi lists the catalog's models under, whose model ids are [CatalogModel.id]. */
  const val PROVIDER_ID = "pipkin-local"
}

/**
 * What a model's attention cache takes per token of context at 16 bits: a key and a value for each of its [layers] that
 * attend to the whole conversation. Layers that keep a fixed-size state (Qwen3.5) or attend to a fixed window (Gemma 4)
 * don't grow with the context, so they aren't counted. The numbers come from each model's `config.json`.
 */
private fun attentionCache(layers: Int, kvHeads: Int, headDim: Int): Long =
  2L * layers * kvHeads * headDim * BYTES_PER_16_BIT_VALUE

private const val BYTES_PER_16_BIT_VALUE = 2

private const val GEMMA_4_E2B_LITERT = "litert-community/gemma-4-E2B-it-litert-lm"
private const val GEMMA_4_E4B_LITERT = "litert-community/gemma-4-E4B-it-litert-lm"

/** `Build.SOC_MODEL` of MediaTek's Dimensity 9500, the SoC the MT6993 NPU build is compiled for. */
private const val MEDIATEK_MT6993 = "MT6993"

/** `Build.SOC_MODEL` of the Pixel 10's SoC (`ro.soc.model` on a Pixel 10 reads `Tensor G5`). */
private const val GOOGLE_TENSOR_G5 = "Tensor G5"

private fun huggingFace(repository: String, file: String) = "https://huggingface.co/$repository/resolve/main/$file"

/**
 * A GGUF file's builds for llama.cpp: one file that runs on the GPU or the CPU, chosen when the model loads, so it's
 * downloaded once for both.
 */
private fun llamaBuilds(repository: String, file: String, sha256: String, sizeBytes: Long) =
  listOf(Backend.GPU, Backend.CPU).map { backend ->
    ModelArtifact(
      engine = EngineKind.LLAMA,
      backend = backend,
      url = huggingFace(repository, file),
      sha256 = sha256,
      sizeBytes = sizeBytes,
      fileName = file,
      contextLimit = EngineChoice.LONG_CONTEXT,
    )
  }
