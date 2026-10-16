package io.jeph.pipkin.host

import java.io.IOException
import org.json.JSONObject

/**
 * Why a Termux-side operation could not run. [message] is user-presentable. [command], when set, is a literal Termux
 * command that fixes it, which setup shows in place of the installed build's setup command.
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
    TermuxChanged,
    HelperNotPaired,
    HelperRejected,
    HelperUnreachable,
    HelperPortTaken,
  }

  /** Fixed by a step of onboarding rather than by retrying. */
  val needsSetup: Boolean get() = kind in SETUP_KINDS

  /**
   * Fixed by opening Termux, which starts Pipkin's helper there; Pipkin then switches back on its own. Should the
   * helper still not start, the setup command fixes what Termux reports (no key, Node.js missing, a crashing helper).
   */
  val needsTermuxOpen: Boolean get() = kind == Kind.HelperUnreachable

  /** Fixed in Termux, by setup or by opening it, rather than by retrying; a host connecting proves the fix. */
  val fixedInTermux: Boolean get() = needsSetup || needsTermuxOpen

  class MissingTermux : HostFailure(Kind.MissingTermux, MISSING_TERMUX)

  /** The installed Termux is signed by neither F-Droid nor Google Play, so Pipkin won't run anything in it. */
  class UntrustedTermux : HostFailure(Kind.UntrustedTermux, UNTRUSTED_TERMUX)

  class Permission : HostFailure(Kind.Permission, PERMISSION)

  class ExternalApps : HostFailure(Kind.ExternalApps, EXTERNAL_APPS)

  class Background : HostFailure(Kind.Background, BACKGROUND)

  class TermuxNotInitialized : HostFailure(Kind.TermuxNotInitialized, TERMUX_NOT_INITIALIZED)

  class NodeMissing : HostFailure(Kind.NodeMissing, NODE_MISSING)

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

  /**
   * Termux was reinstalled, or swapped between its F-Droid and Play builds, after Pipkin set it up. Either wipes
   * Termux's home folder, so Pi, its sign-in and Pipkin's setup there are gone.
   */
  class TermuxChanged : HostFailure(Kind.TermuxChanged, TERMUX_CHANGED)

  /** Play build: Pipkin holds no pairing with a helper in Termux yet. */
  class HelperNotPaired : HostFailure(Kind.HelperNotPaired, HELPER_NOT_PAIRED)

  /** Play build: the helper answered but holds another pairing key, e.g. after Pipkin's data was cleared. */
  class HelperRejected : HostFailure(Kind.HelperRejected, HELPER_REJECTED)

  /** Play build: nothing listens on the helper's ports, so Termux or the helper isn't running. */
  class HelperUnreachable : HostFailure(Kind.HelperUnreachable, HELPER_UNREACHABLE)

  /** Play build: every helper port is held by something other than Pipkin's helper. */
  class HelperPortTaken : HostFailure(Kind.HelperPortTaken, HELPER_PORT_TAKEN)

  private companion object {
    const val MISSING_TERMUX = "Termux is not installed. Install it from F-Droid or Google Play."
    const val UNTRUSTED_TERMUX = "For your security, Pipkin only runs in the official F-Droid or Google Play build " +
      "of Termux, and the Termux on this phone is neither. Reinstall Termux from F-Droid or Google Play."
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
    const val TERMUX_CHANGED = "Termux was reinstalled, which removed Pi, its sign-in and Pipkin's setup in Termux. " +
      "Run the setup command in Termux again."
    const val HELPER_NOT_PAIRED = "Pipkin isn't set up in Termux yet. Run the setup command in Termux."
    const val HELPER_REJECTED = "Termux's Pipkin helper doesn't recognize this Pipkin. " +
      "Run the setup command in Termux again."
    const val HELPER_UNREACHABLE = "Pipkin's helper isn't running in Termux. Open Termux, then switch back to Pipkin."
    const val HELPER_PORT_TAKEN = "Another app is using the ports Pipkin's helper needs. " +
      "Restart your phone, then retry."

    val SETUP_KINDS = setOf(
      Kind.MissingTermux,
      Kind.UntrustedTermux,
      Kind.ExternalApps,
      Kind.Permission,
      Kind.NodeMissing,
      Kind.NodeTooOld,
      Kind.StorageDenied,
      Kind.TermuxNotInitialized,
      Kind.TermuxChanged,
      Kind.HelperNotPaired,
      Kind.HelperRejected,
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
