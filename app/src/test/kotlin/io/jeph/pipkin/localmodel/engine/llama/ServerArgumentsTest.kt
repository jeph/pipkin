package io.jeph.pipkin.localmodel.engine.llama

import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineConfig
import io.jeph.pipkin.localmodel.engine.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerArgumentsTest {
  private val config = EngineConfig(
    engine = EngineKind.LLAMA,
    modelPath = "/data/models/qwen3.gguf",
    modelAlias = "qwen3-0.6b",
    contextSize = 8192,
    threads = 4,
    backend = Backend.CPU,
    port = 18080,
    apiKey = "secret",
  )

  @Test
  fun theServerServesTheConfiguredModelOnLoopbackBehindTheKey() {
    val options = serverArguments(config).options()

    assertEquals("/data/models/qwen3.gguf", options["--model"])
    assertEquals("qwen3-0.6b", options["--alias"])
    assertEquals("127.0.0.1", options["--host"])
    assertEquals("18080", options["--port"])
    assertEquals("secret", options["--api-key"])
    assertEquals("8192", options["--ctx-size"])
    assertEquals("4", options["--threads"])
  }

  @Test
  fun oneSlotKeepsAnEightBitCacheAndReasonsSeparately() {
    val options = serverArguments(config).options()

    assertEquals("1", options["--parallel"])
    assertEquals("q8_0", options["--cache-type-k"])
    assertEquals("q8_0", options["--cache-type-v"])
    assertEquals("on", options["--flash-attn"])
    assertEquals("deepseek", options["--reasoning-format"])
    assertTrue("--jinja" in options)
    assertTrue("--no-ui" in options)
  }

  @Test
  fun noPromptStatesAreCopiedToRam() {
    // Leaving the option out isn't the same: llama.cpp then keeps up to 8 GiB of them.
    assertEquals("0", serverArguments(config).options()["--cache-ram"])
  }

  @Test
  fun theCpuOffloadsToNoDevice() {
    val options = serverArguments(config).options()

    assertEquals("none", options["--device"])
    assertFalse("--gpu-layers" in options)
  }

  @Test
  fun theGpuTakesEveryLayerOnTheOpenClDevice() {
    val options = serverArguments(config.copy(backend = Backend.GPU)).options()

    assertEquals("GPUOpenCL", options["--device"])
    assertEquals("all", options["--gpu-layers"])
  }

  /** Each `--option` to the value after it, or to null when the next argument is another option. */
  private fun List<String>.options(): Map<String, String?> = mapIndexedNotNull { index, argument ->
    if (!argument.startsWith("--")) return@mapIndexedNotNull null
    argument to getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
  }.toMap()
}
