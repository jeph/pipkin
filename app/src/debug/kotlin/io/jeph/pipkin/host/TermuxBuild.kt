package io.jeph.pipkin.host

/**
 * Pipkin Dev's names in Termux, apart from the release's (src/release) so both builds can share one Termux: Pi's
 * config and the helper under `~/.pipkin-dev`, the host under `~/.local/share/pipkin-dev`, `pipkin-dev-start`.
 */
internal object TermuxBuild {
  const val NAME = "pipkin-dev"

  /** The first of the five loopback ports the Play-Termux helper may listen on. */
  const val HELPER_FIRST_PORT = 47320

  /** The loopback port Pi reaches Pipkin's local models on, just past the helper's. */
  const val LOCAL_MODEL_PORT = 47325
}
