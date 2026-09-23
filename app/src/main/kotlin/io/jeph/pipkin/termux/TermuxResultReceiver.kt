package io.jeph.pipkin.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/**
 * Explicit mutable PendingIntent target; the manifest keeps this receiver unexported. Termux
 * fires it when the launched command exits, so with a resident daemon a receipt means the
 * daemon is gone: a launch failure while starting, an exit notification once connected.
 */
class TermuxResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.data?.lastPathSegment ?: return
    val callback = callbacks.remove(id) ?: return
    val result = intent.getBundleExtra("result")
    val code = result?.getInt("exitCode", -1) ?: -1
    val err = result?.getInt("err", 0) ?: -1
    // Termux errors may contain the full command. Never propagate or log raw result text.
    val detail = result?.getString("errmsg").orEmpty().take(8192)
    val failure = when {
      detail.contains("allow-external-apps", ignoreCase = true) -> RuntimeFailure(
        RuntimeFailureKind.ExternalApps,
        "Termux blocked external commands. Run the setup command shown below in Termux, then retry.",
      )

      detail.contains("permission", ignoreCase = true) -> RuntimeFailure(
        RuntimeFailureKind.Permission,
        "Termux denied the run-command permission. Grant it in Android settings.",
      )

      code == 70 -> RuntimeFailure(
        RuntimeFailureKind.Setup,
        "Termux is not initialized. Open Termux once and let its bootstrap finish.",
      )

      code == 72 || code == 74 -> RuntimeFailure(
        RuntimeFailureKind.Prerequisites,
        "Node.js or npm is missing in Termux. Run the install command shown below in Termux, then retry.",
      )

      code == 73 -> RuntimeFailure(
        RuntimeFailureKind.Prerequisites,
        "Termux Node.js must be 22.19.0 or newer. Update it in Termux.",
      )

      code == 78 -> RuntimeFailure(
        RuntimeFailureKind.Endpoint,
        "Another process is using Pipkin's paired port. Choose Re-pair to pick a new port.",
      )

      code == 79 -> RuntimeFailure(
        RuntimeFailureKind.Endpoint,
        "A supervisor still owns Pipkin's data root. Stop the old supervisor in Termux, then retry; Re-pair cannot bypass its lock.",
      )

      code == 80 -> RuntimeFailure(
        RuntimeFailureKind.Authentication,
        "The supervisor did not receive valid TLS credentials. Retry.",
      )

      err != 0 -> RuntimeFailure(
        RuntimeFailureKind.ExternalApps,
        "Termux rejected the command. Open Termux once, grant the run-command permission and enable allow-external-apps.",
      )

      else -> RuntimeFailure(RuntimeFailureKind.Transport, "The supervisor exited with status $code.")
    }
    callback(failure)
  }

  internal companion object {
    private val callbacks = ConcurrentHashMap<String, (RuntimeFailure) -> Unit>()

    fun register(id: String, callback: (RuntimeFailure) -> Unit) {
      check(callbacks.putIfAbsent(id, callback) == null)
    }

    fun unregister(id: String) {
      callbacks -= id
    }
  }
}
