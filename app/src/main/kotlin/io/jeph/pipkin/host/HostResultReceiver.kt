package io.jeph.pipkin.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Explicit mutable PendingIntent target; the manifest keeps this receiver unexported. Termux fires
 * it when a launched `host.mjs` command exits. Before the host connects back a receipt means the
 * launch failed; after, [HostLauncher] only has the exit logged.
 */
class HostResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.data?.lastPathSegment ?: return
    callbacks.remove(id)?.invoke(intent.getBundleExtra("result"))
  }

  internal companion object {
    private const val TAG = "Pipkin"
    private const val ERRMSG_LIMIT = 8192
    private const val STDERR_LOG_LIMIT = 4096

    /** Termux's `Errno.ERRNO_SUCCESS` is `Activity.RESULT_OK` (-1); any other value is a Termux-side error. */
    private const val ERRNO_SUCCESS = -1

    /** Termux's `Errno.ERRNO_CANCELLED` (`Activity.RESULT_CANCELED`): the command was queued as Termux's service stopped. */
    private const val ERRNO_CANCELLED = 0

    /** Termux's exit code for a command it SIGKILLed because its service was stopping. */
    private const val EXIT_SIGKILL = 137

    private val callbacks = ConcurrentHashMap<String, (Bundle?) -> Unit>()

    /** Until the host connects, its exit means the launch failed: [onFailure] hears why. */
    fun register(id: String, onFailure: (HostFailure) -> Unit) {
      check(
        callbacks.putIfAbsent(id) { result ->
          // Termux results may contain the full command (and so the host script); never log them.
          val exitCode = result?.getInt("exitCode", -1) ?: -1
          val err = result?.getInt("err", ERRNO_SUCCESS) ?: ERRNO_SUCCESS
          val failure = failureFor(exitCode, err, result?.getString("errmsg").orEmpty().take(ERRMSG_LIMIT))
          Log.w(TAG, "Termux host launch failed: exitCode=$exitCode err=$err -> ${failure.kind}")
          onFailure(failure)
        } == null,
      )
    }

    /**
     * Once the host connected, its exit is only logged, with the host's own stderr: its diagnostics say when
     * and why a connection ended. The stderr never carries the command, unlike the rest of the result.
     */
    fun logExit(id: String, mode: String) {
      callbacks[id] = { result ->
        val exitCode = result?.getInt("exitCode", -1) ?: -1
        val stderr = result?.getString("stderr").orEmpty().takeLast(STDERR_LOG_LIMIT).trimEnd()
        Log.i(TAG, "Termux host $mode exited: exitCode=$exitCode stderr:\n$stderr")
      }
    }

    fun unregister(id: String) {
      callbacks -= id
    }

    /**
     * Exit codes come from the launcher's BOOTSTRAP script. Termux stops its service whenever its last
     * command exits with no terminal open, so a host launched in that instant is cancelled or killed;
     * that is [HostFailure.Interrupted], not a setup problem.
     */
    fun failureFor(exitCode: Int, err: Int, errmsg: String): HostFailure = when {
      errmsg.contains("allow-external-apps", ignoreCase = true) -> HostFailure.ExternalApps()
      err == ERRNO_CANCELLED || exitCode == EXIT_SIGKILL -> HostFailure.Interrupted()
      err != ERRNO_SUCCESS -> HostFailure.TermuxError()
      exitCode == 70 -> HostFailure.TermuxNotInitialized()
      exitCode == 72 || exitCode == 74 -> HostFailure.NodeMissing()
      exitCode == 73 -> HostFailure.NodeTooOld()
      exitCode == 75 -> HostFailure.StorageDenied()
      exitCode == 80 -> HostFailure.Internal()
      else -> HostFailure.Crashed(exitCode)
    }

    /** A host that exited before connecting with no Termux-side error, as Pipkin's helper reports it. */
    fun exitFailure(exitCode: Int): HostFailure = failureFor(exitCode, ERRNO_SUCCESS, "")
  }
}
