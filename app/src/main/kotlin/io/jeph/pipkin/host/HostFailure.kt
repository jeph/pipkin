package io.jeph.pipkin.host

import io.jeph.pipkin.termux.TermuxSetup
import java.io.IOException
import org.json.JSONObject

/**
 * Why a Termux-side operation could not run. [message] is user-presentable; [command], when set,
 * is the Termux command the UI offers to copy before opening Termux.
 */
internal sealed class HostFailure(
  val kind: Kind,
  message: String,
  val command: String? = null,
  cause: Throwable? = null,
) : IOException(message, cause) {
  enum class Kind {
    MissingTermux,
    UntrustedTermux,
    Permission,
    ExternalApps,
    Background,
    TermuxNotInitialized,
    NodeMissing,
    NodeTooOld,
    StorageDenied,
    Internal,
    Crashed,
    Timeout,
    Disconnected,
    Interrupted,
    TermuxError,
    ConfigMove,
  }

  /** Fixed by a step of onboarding rather than by retrying. */
  val needsSetup: Boolean get() = kind in SETUP_KINDS

  class MissingTermux : HostFailure(Kind.MissingTermux, MISSING_TERMUX)

  /** The installed Termux isn't signed by F-Droid, so Pipkin won't run anything in it. */
  class UntrustedTermux : HostFailure(Kind.UntrustedTermux, UNTRUSTED_TERMUX)

  class Permission : HostFailure(Kind.Permission, PERMISSION)

  class ExternalApps : HostFailure(Kind.ExternalApps, EXTERNAL_APPS, TermuxSetup.COMMAND)

  class Background : HostFailure(Kind.Background, BACKGROUND)

  class TermuxNotInitialized : HostFailure(Kind.TermuxNotInitialized, TERMUX_NOT_INITIALIZED)

  class NodeMissing : HostFailure(Kind.NodeMissing, NODE_MISSING, TermuxSetup.COMMAND)

  class NodeTooOld : HostFailure(Kind.NodeTooOld, NODE_TOO_OLD, "yes | pkg upgrade -y")

  class StorageDenied : HostFailure(Kind.StorageDenied, STORAGE_DENIED, "termux-setup-storage")

  class Internal : HostFailure(Kind.Internal, INTERNAL)

  class Crashed(val exitCode: Int) : HostFailure(Kind.Crashed, "$CRASHED $exitCode before connecting.")

  class Timeout : HostFailure(Kind.Timeout, TIMEOUT)

  class Disconnected(cause: Throwable? = null) : HostFailure(Kind.Disconnected, DISCONNECTED, cause = cause)

  /** Termux stopped its service as the host was launched; [HostLauncher] retries these. */
  class Interrupted : HostFailure(Kind.Interrupted, INTERRUPTED)

  /** Termux refused the command for a reason other than `allow-external-apps`. */
  class TermuxError : HostFailure(Kind.TermuxError, TERMUX_ERROR)

  /** Pi's config folder could not move off shared storage into Termux, so the host runs nothing else until it has. */
  class ConfigMove(reason: String, cause: Throwable? = null) :
    HostFailure(Kind.ConfigMove, "$CONFIG_MOVE $reason", cause = cause)

  private companion object {
    const val MISSING_TERMUX = "Termux is not installed. Install it from F-Droid."
    const val UNTRUSTED_TERMUX = "For your security, Pipkin only runs in the official F-Droid build of Termux, " +
      "and the Termux on this phone isn't one. Reinstall Termux from F-Droid to keep using Pipkin."
    const val PERMISSION = "Allow Pipkin to run commands in Termux in Android settings, then retry."
    const val EXTERNAL_APPS = "Termux blocked Pipkin's commands. Run the setup command in Termux, then retry."
    const val BACKGROUND = "Android blocked starting Termux in the background. Open Pipkin and retry."
    const val INTERRUPTED = "Termux stopped while starting Pipkin's helper. Retry."
    const val TERMUX_ERROR = "Termux couldn't run Pipkin's helper. Retry."
    const val TERMUX_NOT_INITIALIZED = "Open Termux once and let it finish installing."
    const val NODE_MISSING = "Node.js is missing in Termux. Run the setup command in Termux, then retry."
    const val NODE_TOO_OLD = "Node.js can't run or is too old. Run `yes | pkg upgrade -y` in Termux, then retry."
    const val STORAGE_DENIED =
      "Termux can't reach shared storage. Run `termux-setup-storage` in Termux and allow access."
    const val INTERNAL = "Pipkin's Termux helper did not receive its token. Retry."
    const val CRASHED = "Pipkin's Termux helper exited with status"
    const val TIMEOUT = "Termux did not respond in time. Open Termux once, then retry."
    const val DISCONNECTED = "The connection to Termux closed unexpectedly. Retry."
    const val CONFIG_MOVE = "Pipkin couldn't move Pi's settings from shared storage into Termux:"

    val SETUP_KINDS = setOf(
      Kind.UntrustedTermux,
      Kind.ExternalApps,
      Kind.Permission,
      Kind.NodeMissing,
      Kind.NodeTooOld,
      Kind.StorageDenied,
      Kind.TermuxNotInitialized,
    )
  }
}

/** The host ran the operation and refused it; [code] is the host's machine code (`pi_version`, `bad_id`, …). */
internal class HostRejected(val code: String, val error: String) : Exception(error) {
  companion object {
    /** Null for an `ok:true` `pipkin_result`. */
    fun from(result: JSONObject): HostRejected? = if (result.optBoolean("ok")) {
      null
    } else {
      HostRejected(result.optString("code", "error"), result.optString("error", "The Termux helper failed."))
    }
  }
}
