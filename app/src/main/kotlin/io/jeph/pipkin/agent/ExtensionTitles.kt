package io.jeph.pipkin.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The extension titles of chats whose Pi Pipkin stopped (parked, reloaded, closed), by session file. Pipkin restarts Pi
 * far more often than someone running it in a terminal would, so a title outlives those restarts as it would in a Pi
 * that kept running. Kept in memory only: like Pi itself, a restarted app starts without them.
 */
internal class ExtensionTitles {
  private val state = MutableStateFlow<Map<String, String>>(emptyMap())

  val bySessionFile: StateFlow<Map<String, String>> = state.asStateFlow()

  operator fun get(sessionFile: String): String? = state.value[sessionFile]

  /** Keeps [title] for [sessionFile]; null forgets the session's title. */
  fun keep(sessionFile: String, title: String?) {
    state.update { if (title == null) it - sessionFile else it + (sessionFile to title) }
  }
}
