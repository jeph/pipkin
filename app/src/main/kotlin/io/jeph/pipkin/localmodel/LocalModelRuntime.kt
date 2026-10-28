package io.jeph.pipkin.localmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** SKELETON: the lead replaces this with the scheduler, the `:inference` process and the proxy. */
internal class LocalModelRuntime {
  private val stateFlow = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
  private val sessionsFlow = MutableStateFlow<Map<String, SessionActivity>>(emptyMap())

  /** What the `:inference` process is doing. */
  val state: StateFlow<RuntimeState> = stateFlow

  /** Each Pi session's request in flight, keyed by Pi's session id. */
  val sessions: StateFlow<Map<String, SessionActivity>> = sessionsFlow

  /** Kills the `:inference` process, failing whatever it was serving. */
  fun unload() = Unit
}
