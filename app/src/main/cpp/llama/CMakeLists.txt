# pipkin-llama: llama.cpp's own OpenAI-compatible server (tools/server), run in the :inference process through JNI,
# with llama.cpp, ggml and the server linked in statically so that the app ships one library per ABI.

# The pinned sources fetched by Gradle's llamaSources task.
if(NOT LLAMA_SOURCES)
  message(FATAL_ERROR "LLAMA_SOURCES is not set")
endif()

# Debug builds would otherwise compile llama.cpp at -O0, where a reply crawls out at a few tokens a minute, and release
# builds at RelWithDebInfo's -O2. These variables only reach this directory and llama.cpp's, not pipkin-syntax.
foreach(lang C CXX)
  set(CMAKE_${lang}_FLAGS_DEBUG "-O3 -DNDEBUG")
  set(CMAKE_${lang}_FLAGS_RELWITHDEBINFO "-O3 -DNDEBUG")
endforeach()

# The server and the libraries it needs, without the web UI (LLAMA_USE_PREBUILT_UI would download it from Hugging
# Face), HTTPS, subprocesses (MCP tools, router mode, video) or OpenMP, whose runtime would be one more library.
set(BUILD_SHARED_LIBS OFF)
set(LLAMA_BUILD_COMMON ON)
set(LLAMA_BUILD_TOOLS ON)
set(LLAMA_BUILD_SERVER ON)
set(LLAMA_BUILD_UI OFF)
set(LLAMA_USE_PREBUILT_UI OFF)
set(LLAMA_OPENSSL OFF)
set(LLAMA_SUBPROCESS OFF)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_APP OFF)
set(LLAMA_ALL_WARNINGS OFF)
set(GGML_OPENMP OFF)
set(GGML_NATIVE OFF)

# A release archive has no git history, and build-info.cmake would otherwise report Pipkin's own commit, whose
# repository the sources are extracted inside.
set(LLAMA_BUILD_IS_DEV OFF)
set(LLAMA_BUILD_NUMBER 0)
set(LLAMA_BUILD_COMMIT v0.8.0)

# CPU features on arm64. ggml's CPU backend picks its NEON kernels when it compiles, so a library built for one
# feature level dies of SIGILL on a CPU without it. ggml's own answer, GGML_CPU_ALL_VARIANTS with GGML_BACKEND_DL,
# builds seven copies of the backend and dlopens the best one; it needs BUILD_SHARED_LIBS, so llama.cpp, ggml and the
# server become a dozen libraries, each with its own copy of the static C++ runtime passing exceptions to the next, and
# it finds the variants by listing nativeLibraryDir, which only works while the APK stores its libraries compressed.
# Instead the backend is built once for ARMv8.2 with dot product and half-precision arithmetic, which every arm64
# phone core since the Cortex-A55 and A75 (2017) has and which attention and quantization lean on, and KleidiAI adds
# the matrix multiplications of Q4_0, Q4_K, Q6_K and Q8_0 weights for dot product, int8 matrix multiply (i8mm), SVE
# and SME2, choosing among them when the model loads. LlamaEngine refuses to load the library on an older core.
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(GGML_CPU_ARM_ARCH armv8.2-a+dotprod+fp16)
  set(GGML_CPU_KLEIDIAI ON)
  # ggml fetches KleidiAI with FetchContent; this points it at Gradle's pinned copy and keeps CMake offline.
  set(FETCHCONTENT_SOURCE_DIR_KLEIDIAI ${LLAMA_SOURCES}/kleidiai)
  set(FETCHCONTENT_FULLY_DISCONNECTED ON)

  # ggml's OpenCL backend, for the Adreno GPUs of Snapdragon phones, with the kernels compiled into the library and
  # those written for Adreno, as llama.cpp's docs/backend/OPENCL.md builds it for Android. It drops any other GPU.
  # Linking the vendor's libOpenCL.so would keep the library from loading on a phone without one, and GGML_BACKEND_DL
  # is ruled out above, so FindOpenCL takes ../opencl's loader for the library, which opens the driver on first use;
  # without one, the backend finds no platform and registers no device.
  set(GGML_OPENCL ON)
  set(GGML_OPENCL_EMBED_KERNELS ON)
  set(GGML_OPENCL_USE_ADRENO_KERNELS ON)
  set(OpenCL_INCLUDE_DIR ${OPENCL_HEADERS} CACHE PATH "Khronos's OpenCL headers" FORCE)
  set(OpenCL_LIBRARY pipkin-opencl-loader CACHE STRING "Pipkin's OpenCL loader" FORCE)
endif()
# x86_64 runs on emulators and Chromebooks, keeps the NDK's baseline of SSE4.2, and has no GPU backend.

add_subdirectory(${LLAMA_SOURCES}/llama.cpp llama.cpp EXCLUDE_FROM_ALL)

add_library(pipkin-llama SHARED llama_jni.cpp)

target_compile_features(pipkin-llama PRIVATE cxx_std_17)
target_compile_options(pipkin-llama PRIVATE -Wall -Wextra -Werror)
target_link_libraries(pipkin-llama PRIVATE llama-server-impl log)

target_link_options(pipkin-llama PRIVATE
  # The server replaces common_params.load_progress_callback with its own, which reports only to a router process;
  # llama_jni.cpp wraps the model loader to read the progress on the way.
  -Wl,--wrap=llama_model_load_from_file
  # Only the JNI functions stay exported, so the linker drops whatever the server never calls.
  -Wl,--exclude-libs,ALL
)
