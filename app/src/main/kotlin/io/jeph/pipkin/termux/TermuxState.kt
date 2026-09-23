package io.jeph.pipkin.termux

import org.json.JSONObject

internal enum class ConnectionPhase {
  Unavailable,
  NeedsConsent,
  Disconnected,
  Launching,
  Connecting,
  Preparing,
  Connected,
}

internal data class GenerationInfo(
  val generation: Long,
  val workspace: String,
  val pid: Long,
  val startedAt: Long,
  val running: Boolean,
  /** Between Pi's `agent_start` and `agent_settled` (or exit). Drives the foreground service. */
  val working: Boolean,
  val code: Long?,
  val signal: String?,
  val lastSeq: Long,
)

internal data class TermuxState(
  val phase: ConnectionPhase = ConnectionPhase.Disconnected,
  /** Last failure; cleared on a successful connect. */
  val failure: RuntimeFailure? = null,
  /** Latest `runtime.ensure` progress line. */
  val progress: String? = null,
  /** Last `status` result: instance, pid, piVersion, ... */
  val daemon: JSONObject? = null,
  val generations: List<GenerationInfo> = emptyList(),
  val unresolvedReceipts: Int = 0,
  val port: Int = 0,
) {
  val busy: Boolean get() = generations.any { it.working }
}
