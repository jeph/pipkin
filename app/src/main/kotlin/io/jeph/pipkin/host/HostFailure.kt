package io.jeph.pipkin.host

import androidx.annotation.StringRes
import io.jeph.pipkin.AppStrings
import io.jeph.pipkin.R
import java.io.IOException

/**
 * Why a Termux-side operation could not run. [message] is user-presentable, in the app's language when it is read.
 * [command], when set, is a literal Termux command that fixes it, which setup shows in place of the installed build's
 * setup command.
 */
internal sealed class HostFailure(
  val kind: Kind,
  @StringRes protected val messageRes: Int,
  val command: String? = null,
  cause: Throwable? = null,
) : IOException(null, cause) {
  /** A failure whose [messageRes] has placeholders overrides this to fill them. */
  override val message: String get() = AppStrings.get(messageRes)

  enum class Kind {
    MissingTermux,
    UntrustedTermux,
    Permission,
    ExternalApps,
    Background,
    TermuxNotInitialized,
    NodeMissing,
    NodeTooOld,
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

  class MissingTermux : HostFailure(Kind.MissingTermux, R.string.host_failure_missing_termux)

  /** The installed Termux is signed by neither F-Droid nor Google Play, so Pipkin won't run anything in it. */
  class UntrustedTermux : HostFailure(Kind.UntrustedTermux, R.string.host_failure_untrusted_termux)

  class Permission : HostFailure(Kind.Permission, R.string.host_failure_permission)

  class ExternalApps : HostFailure(Kind.ExternalApps, R.string.host_failure_external_apps)

  class Background : HostFailure(Kind.Background, R.string.host_failure_background)

  class TermuxNotInitialized : HostFailure(Kind.TermuxNotInitialized, R.string.host_failure_termux_not_initialized)

  class NodeMissing : HostFailure(Kind.NodeMissing, R.string.host_failure_node_missing)

  class NodeTooOld : HostFailure(Kind.NodeTooOld, R.string.host_failure_node_too_old, UPGRADE_COMMAND) {
    override val message: String get() = AppStrings.get(messageRes, UPGRADE_COMMAND)
  }

  class Internal : HostFailure(Kind.Internal, R.string.host_failure_internal)

  class Crashed(val exitCode: Int) : HostFailure(Kind.Crashed, R.string.host_failure_crashed) {
    override val message: String get() = AppStrings.get(messageRes, exitCode)
  }

  class Timeout : HostFailure(Kind.Timeout, R.string.host_failure_timeout)

  class Disconnected(cause: Throwable? = null) :
    HostFailure(Kind.Disconnected, R.string.host_failure_disconnected, cause = cause)

  /** Termux stopped its service as the host was launched; [HostLauncher] retries these. */
  class Interrupted : HostFailure(Kind.Interrupted, R.string.host_failure_interrupted)

  /** Termux refused the command for a reason other than `allow-external-apps`. */
  class TermuxError : HostFailure(Kind.TermuxError, R.string.host_failure_termux_error)

  /**
   * Termux was reinstalled, or swapped between its F-Droid and Play builds, after Pipkin set it up. Either wipes
   * Termux's home folder, so Pi, its sign-in and Pipkin's setup there are gone.
   */
  class TermuxChanged : HostFailure(Kind.TermuxChanged, R.string.host_failure_termux_changed)

  /** Play build: Pipkin holds no pairing with a helper in Termux yet. */
  class HelperNotPaired : HostFailure(Kind.HelperNotPaired, R.string.host_failure_helper_not_paired)

  /** Play build: the helper answered but holds another pairing key, e.g. after Pipkin's data was cleared. */
  class HelperRejected : HostFailure(Kind.HelperRejected, R.string.host_failure_helper_rejected)

  /** Play build: nothing listens on the helper's ports, so Termux or the helper isn't running. */
  class HelperUnreachable : HostFailure(Kind.HelperUnreachable, R.string.host_failure_helper_unreachable)

  /** Play build: every helper port is held by something other than Pipkin's helper. */
  class HelperPortTaken : HostFailure(Kind.HelperPortTaken, R.string.host_failure_helper_port_taken)

  private companion object {
    const val UPGRADE_COMMAND = "yes | pkg upgrade -y"

    val SETUP_KINDS = setOf(
      Kind.MissingTermux,
      Kind.UntrustedTermux,
      Kind.ExternalApps,
      Kind.Permission,
      Kind.NodeMissing,
      Kind.NodeTooOld,
      Kind.TermuxNotInitialized,
      Kind.TermuxChanged,
      Kind.HelperNotPaired,
      Kind.HelperRejected,
    )
  }
}
