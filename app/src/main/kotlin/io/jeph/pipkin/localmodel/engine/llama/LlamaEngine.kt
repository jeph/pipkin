package io.jeph.pipkin.localmodel.engine.llama

import android.system.Os
import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineConfig
import io.jeph.pipkin.localmodel.engine.EngineException
import io.jeph.pipkin.localmodel.engine.InferenceEngine
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves a GGUF model with llama.cpp's own OpenAI-compatible server rather than a translation of it, so tool calls,
 * reasoning and prompt caching behave as they do in llama.cpp. The server runs on a thread of the `:inference` process
 * until the process dies, which is how a model unloads.
 */
internal class LlamaEngine : InferenceEngine {
  override fun start(config: EngineConfig, onProgress: (Float) -> Unit) {
    if (config.backend == Backend.NPU) {
      throw EngineException("backend_unsupported", "llama.cpp runs models on the CPU or the GPU, not the NPU")
    }
    // On the GPU too, since the CPU backend still runs the token embeddings and whatever the GPU's backend lacks.
    if (!cpuSupported()) {
      throw EngineException("cpu_unsupported", "llama.cpp needs an ARMv8.2 CPU with dot product and FP16 instructions")
    }
    // ggml-opencl compiles its kernels for the GPU as the model loads, which takes a while the first time, and caches
    // the programs under TMPDIR, which Android sets to the app's cache directory.
    if (config.backend == Backend.GPU && LlamaServer.gpuName() == null) {
      throw EngineException("backend_unsupported", "llama.cpp runs on the GPU only with an Adreno GPU's OpenCL driver")
    }
    // Read by the OpenCL loader (app/src/main/cpp/opencl) before ggml first asks it for a platform.
    if (config.backend == Backend.CPU) Os.setenv("PIPKIN_OPENCL", "off", true)
    val exitStatus = AtomicInteger(RUNNING)
    val server = Thread(
      null,
      { exitStatus.set(LlamaServer.run(serverArguments(config).toTypedArray())) },
      "llama-server",
      SERVER_STACK_BYTES,
    )
    server.start()
    val timeoutSeconds = if (config.backend == Backend.GPU) GPU_LOAD_TIMEOUT_SECONDS else LOAD_TIMEOUT_SECONDS
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    var reported = -1f
    // llama-server binds its port before it loads the model, and exits if it can't, so once the weights are in, a 200
    // from the port is this server's rather than another process's still holding it.
    while (reported < 1f || !answersHealth(config)) {
      server.join(POLL_MILLIS)
      val status = exitStatus.get()
      if (status != RUNNING) {
        val reason = LlamaServer.firstError() ?: "no error logged"
        throw EngineException("load_failed", "llama-server exited with status $status: $reason")
      }
      if (System.nanoTime() > deadline) {
        LlamaServer.terminate()
        throw EngineException("load_timeout", "llama-server didn't load the model in $timeoutSeconds s")
      }
      val progress = LlamaServer.loadProgress()
      if (progress != reported) {
        reported = progress
        onProgress(progress)
      }
    }
  }

  /**
   * Whether `GET /health` answers 200, which llama-server does once the model has loaded and warmed up. A plain socket,
   * since Android's HttpURLConnection refuses cleartext HTTP, even to loopback, without a network security config.
   */
  private fun answersHealth(config: EngineConfig): Boolean = try {
    Socket().use { socket ->
      // llama-server listens on IPv4, and Android's getLoopbackAddress() is ::1.
      socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port), HEALTH_TIMEOUT_MILLIS)
      socket.soTimeout = HEALTH_TIMEOUT_MILLIS
      val request = "GET /health HTTP/1.1\r\nHost: 127.0.0.1:${config.port}\r\n" +
        "Authorization: Bearer ${config.apiKey}\r\nConnection: close\r\n\r\n"
      socket.getOutputStream().write(request.toByteArray())
      val statusLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
      statusLine.split(' ').getOrNull(1) == "200"
    }
  } catch (_: IOException) {
    false
  }

  /**
   * Whether this CPU runs the library: on arm64, ggml's CPU backend is built for ARMv8.2 with dot product and FP16
   * vector arithmetic (app/src/main/cpp/llama/CMakeLists.txt), and an older core would die of SIGILL. Every core has to
   * have them, as the server's threads run on any. `/proc/cpuinfo` lists each core's features; `/proc/self/auxv` would
   * say the same, but a release build's process isn't dumpable, so the kernel makes that file root's.
   */
  private fun cpuSupported(): Boolean {
    if (System.getProperty("os.arch") != "aarch64") return true
    val cores = try {
      File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }
    } catch (_: IOException) {
      return false
    }
    return cores.isNotEmpty() && cores.all { line ->
      val features = line.substringAfter(':').trim().split(' ').toSet()
      features.containsAll(REQUIRED_FEATURES)
    }
  }

  private companion object {
    const val RUNNING = Int.MIN_VALUE
    const val LOAD_TIMEOUT_SECONDS = 120L

    /** Longer, since the first load on a GPU also compiles ggml-opencl's kernels for it. */
    const val GPU_LOAD_TIMEOUT_SECONDS = 300L
    const val POLL_MILLIS = 100L
    const val HEALTH_TIMEOUT_MILLIS = 1000

    /** As much stack as a program's main thread gets on Linux, where llama-server normally runs its model loop. */
    const val SERVER_STACK_BYTES = 8L * 1024 * 1024

    /** FP16 vector arithmetic and dot product, as the kernel names them in `/proc/cpuinfo`. */
    val REQUIRED_FEATURES = setOf("asimdhp", "asimddp")
  }
}

/** llama-server's command line for [config], without the program name. */
internal fun serverArguments(config: EngineConfig): List<String> = listOf(
  "--model", config.modelPath,
  "--alias", config.modelAlias,
  "--host", "127.0.0.1",
  "--port", config.port.toString(),
  "--api-key", config.apiKey,
  "--ctx-size", config.contextSize.toString(),
  "--threads", config.threads.toString(),
  // One request at a time, the scheduler's queue in the main process holding the rest, so a slot gets the whole context.
  "--parallel", "1",
  "--jinja",
  "--reasoning-format", "deepseek",
  // An 8-bit KV cache holds twice the context of the default 16-bit one in the same memory, at a negligible loss of
  // quality. llama.cpp quantizes the V cache only with flash attention.
  "--cache-type-k", "q8_0",
  "--cache-type-v", "q8_0",
  "--flash-attn", "on",
  // The slot reuses the cached prompt's longest common prefix by default; this also reuses chunks of 256 tokens or
  // more after a change in the middle, such as a compacted or edited message, by shifting their KV entries.
  "--cache-reuse", "256",
  // No copies of prompt states in RAM for other conversations to come back to. llama.cpp copies the slot's state out
  // when another chat's request takes the slot, a burst of memory on top of the model's that on a phone gets apps, then
  // the model, stopped, and a 4B model's cache for Pi's prompt alone outgrows any budget a phone could spare. 0 turns
  // them off; leaving the option out allows 8 GiB.
  "--cache-ram", "0",
  // Weights the CPU kernels read as the file lays them out, such as the token embeddings, stay in the page cache, which
  // Android can drop and read again; KleidiAI and ggml copy the rest into their own layouts in the process's memory,
  // or the GPU's.
  "--load-mode", "mmap",
  "--no-ui",
  "--no-slots",
  "--log-colors", "off",
) + deviceArguments(config.backend)

/**
 * On the GPU, every layer goes to the device of ggml's OpenCL backend, leaving the CPU the token embeddings and any
 * operation the GPU's kernels lack. On the CPU, no device gets any, even on a phone that has one.
 */
private fun deviceArguments(backend: Backend): List<String> = if (backend == Backend.GPU) {
  listOf("--device", "GPUOpenCL", "--gpu-layers", "all")
} else {
  listOf("--device", "none")
}
