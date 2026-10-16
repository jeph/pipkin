package io.jeph.pipkin.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What extensions showed in chats whose Pi Pipkin stopped (parked, reloaded, closed), by session file, such as the
 * title or statuses they set. Pipkin restarts Pi far more often than someone running it in a terminal would, so these
 * outlive those restarts as they would in a Pi that kept running. Kept in memory only: like Pi itself, a restarted app
 * starts without them.
 */
internal class KeptBySession<T : Any> {
  private val state = MutableStateFlow<Map<String, T>>(emptyMap())

  val bySessionFile: StateFlow<Map<String, T>> = state.asStateFlow()

  operator fun get(sessionFile: String): T? = state.value[sessionFile]

  /** Keeps [value] for [sessionFile]; null forgets what the session had. */
  fun keep(sessionFile: String, value: T?) {
    state.update { if (value == null) it - sessionFile else it + (sessionFile to value) }
  }

  fun forgetAll() {
    state.value = emptyMap()
  }
}
