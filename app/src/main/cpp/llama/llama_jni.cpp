// JNI bindings for io.jeph.pipkin.localmodel.engine.llama.LlamaServer: llama.cpp's server (tools/server/server.cpp),
// started the way its llama-server binary starts it, inside the :inference process.

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <unistd.h>

#include <atomic>
#include <clocale>
#include <condition_variable>
#include <csignal>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "arg.h"
#include "common.h"
#include "llama.h"
#include "log.h"

// tools/server/server.cpp declares these for llama-cli, which embeds the server without signal handlers; the first
// skips installing them and resolving -hf models, the second ends a running server.
int llama_server(common_params & params, int argc, char ** argv);
void llama_server_terminate();
// tools/server/server-stream.h, whose includes reach into the server's private headers.
void server_stream_session_manager_start();

namespace {

constexpr const char * TAG = "PipkinLlama";

// Written on its own line to stderr by sync_logs; the pump counts them to know it has logged everything before.
constexpr char LOG_SYNC = '\x1e';

std::atomic<float> load_progress{0.0f};
std::atomic<bool> log_pump_running{false};

std::mutex log_mutex;
std::condition_variable log_synced;
uint64_t log_syncs_requested = 0;
uint64_t log_syncs_done = 0;
// llama.cpp logs the cause of a failure first, then each caller on the way out adds that it failed too.
std::string first_error;

// common_log writes "I ", "W ", "E " or "D " before each message once timestamps are off; lines without one
// continue a message or come from ggml's own fprintf(stderr), as when it aborts.
void log_line(std::string & line) {
  if (!line.empty() && line.back() == LOG_SYNC) {
    line.pop_back();
    if (!line.empty()) {
      log_line(line);
    }
    std::lock_guard<std::mutex> lock(log_mutex);
    log_syncs_done++;
    log_synced.notify_all();
    return;
  }
  int priority = ANDROID_LOG_INFO;
  size_t start = 0;
  if (line.size() >= 2 && line[1] == ' ') {
    switch (line[0]) {
      case 'D': priority = ANDROID_LOG_DEBUG; start = 2; break;
      case 'I': priority = ANDROID_LOG_INFO; start = 2; break;
      case 'W': priority = ANDROID_LOG_WARN; start = 2; break;
      case 'E': priority = ANDROID_LOG_ERROR; start = 2; break;
      default: break;
    }
  }
  const char * text = line.c_str() + start;
  __android_log_write(priority, TAG, text);
  if (priority == ANDROID_LOG_ERROR) {
    std::lock_guard<std::mutex> lock(log_mutex);
    if (first_error.empty()) {
      first_error = text;
    }
  }
}

void pump_logs(int fd) {
  pthread_setname_np(pthread_self(), "llama-log");
  std::string line;
  char buffer[4096];
  for (;;) {
    const ssize_t n = read(fd, buffer, sizeof(buffer));
    if (n <= 0) {
      return;
    }
    for (ssize_t i = 0; i < n; i++) {
      if (buffer[i] == '\n') {
        log_line(line);
        line.clear();
      } else {
        line.push_back(buffer[i]);
      }
    }
  }
}

// An app's stdout and stderr go to /dev/null, while llama.cpp logs through common_log, which prints to them, and ggml
// prints its assertions there just before it aborts. A pipe brings both to logcat.
void redirect_output_to_logcat() {
  static std::once_flag once;
  std::call_once(once, [] {
    int fds[2];
    if (pipe(fds) != 0) {
      __android_log_write(ANDROID_LOG_WARN, TAG, "Can't create the log pipe; llama.cpp logs nothing");
      return;
    }
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    std::thread(pump_logs, fds[0]).detach();
    log_pump_running.store(true);
  });
}

// Waits until the pump has logged everything llama.cpp has written so far, first_error included.
void sync_logs() {
  common_log_flush(common_log_main());
  if (!log_pump_running.load()) {
    return;
  }
  uint64_t ticket;
  {
    std::lock_guard<std::mutex> lock(log_mutex);
    ticket = ++log_syncs_requested;
  }
  // Outside the lock: with the pipe full, the write waits for the pump, which may be waiting for the lock.
  const char marker[] = {LOG_SYNC, '\n'};
  if (write(STDERR_FILENO, marker, sizeof(marker)) != static_cast<ssize_t>(sizeof(marker))) {
    return;
  }
  std::unique_lock<std::mutex> lock(log_mutex);
  log_synced.wait(lock, [ticket] { return log_syncs_done >= ticket; });
}

struct chained_progress {
  llama_progress_callback callback;
  void * user_data;
};

bool record_progress(float progress, void * user_data) {
  load_progress.store(progress);
  auto * chained = static_cast<chained_progress *>(user_data);
  return chained->callback == nullptr || chained->callback(progress, chained->user_data);
}

}  // namespace

// The server sets common_params.load_progress_callback to its own callback, which tells only a router process,
// so the link wraps the one function that loads models (CMakeLists.txt's --wrap) to read the progress beside it.
extern "C" llama_model * __real_llama_model_load_from_file(const char * path, llama_model_params params);

extern "C" llama_model * __wrap_llama_model_load_from_file(const char * path, llama_model_params params) {
  // Fitting the context to memory loads the model's metadata only, without allocating or reading weights.
  if (params.no_alloc) {
    return __real_llama_model_load_from_file(path, params);
  }
  chained_progress chained{params.progress_callback, params.progress_callback_user_data};
  params.progress_callback = record_progress;
  params.progress_callback_user_data = &chained;
  return __real_llama_model_load_from_file(path, params);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_jeph_pipkin_localmodel_engine_llama_LlamaServer_run(JNIEnv * env, jobject, jobjectArray arguments) {
  redirect_output_to_logcat();

  const jsize count = env->GetArrayLength(arguments);
  std::vector<std::string> strings;
  strings.reserve(count + 1);
  strings.emplace_back("llama-server");
  for (jsize i = 0; i < count; i++) {
    auto * argument = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
    const char * chars = env->GetStringUTFChars(argument, nullptr);
    strings.emplace_back(chars);
    env->ReleaseStringUTFChars(argument, chars);
    env->DeleteLocalRef(argument);
  }
  std::vector<char *> argv;
  argv.reserve(strings.size() + 1);
  for (auto & string : strings) {
    argv.push_back(string.data());
  }
  argv.push_back(nullptr);
  const int argc = static_cast<int>(strings.size());

  // What llama_server(argc, argv) does before it serves, minus the signal handlers: Android owns the process's signals.
  std::setlocale(LC_NUMERIC, "C");
  std::signal(SIGPIPE, SIG_IGN);

  common_params params;
  common_init();
  // logcat stamps each line itself, and the pump reads the level from the prefix that's left.
  common_log_set_timestamps(common_log_main(), false);
  server_stream_session_manager_start();
  if (!common_params_parse(argc, argv.data(), params, LLAMA_EXAMPLE_SERVER)) {
    sync_logs();
    return 1;
  }
  llama_backend_init();
  llama_numa_init(params.numa);

  const int result = llama_server(params, argc, argv.data());
  sync_logs();
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_jeph_pipkin_localmodel_engine_llama_LlamaServer_terminate(JNIEnv *, jobject) {
  llama_server_terminate();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_io_jeph_pipkin_localmodel_engine_llama_LlamaServer_loadProgress(JNIEnv *, jobject) {
  return load_progress.load();
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_jeph_pipkin_localmodel_engine_llama_LlamaServer_firstError(JNIEnv * env, jobject) {
  std::lock_guard<std::mutex> lock(log_mutex);
  return first_error.empty() ? nullptr : env->NewStringUTF(first_error.c_str());
}
