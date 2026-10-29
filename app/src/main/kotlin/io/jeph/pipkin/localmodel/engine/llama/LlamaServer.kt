package io.jeph.pipkin.localmodel.engine.llama

/**
 * llama.cpp's server in `libpipkin-llama.so` (`app/src/main/cpp/llama`). The library loads with this object, on its
 * first use, so that only the `:inference` process that runs a llama.cpp model maps it.
 */
internal object LlamaServer {
  init {
    System.loadLibrary("pipkin-llama")
  }

  /**
   * Runs llama-server with [arguments], its command line without the program name, and returns its exit status: 1 at
   * once when the arguments or the model are bad, otherwise once [terminate] stops it. llama.cpp logs to logcat under
   * `PipkinLlama`.
   */
  external fun run(arguments: Array<String>): Int

  /** Stops a server that has loaded its model; does nothing while it loads. */
  external fun terminate()

  /** How much of the model's weights the server has loaded, from 0 to 1. */
  external fun loadProgress(): Float

  /** The first error llama.cpp logged, the cause of a failure, which says why [run] returned early. */
  external fun firstError(): String?

  /**
   * The name of the GPU that llama-server offloads to on `--device GPUOpenCL`, such as `QUALCOMM Adreno(TM) 840`, or
   * null when ggml's OpenCL backend found none it supports: no OpenCL driver, a GPU other than an Adreno, or x86_64,
   * which is built without the backend.
   */
  external fun gpuName(): String?
}
