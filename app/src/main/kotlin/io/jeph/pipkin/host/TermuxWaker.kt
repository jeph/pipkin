package io.jeph.pipkin.host

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.termux.TermuxInstall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Starts Pipkin's helper in Termux's Play build by opening Termux in a new terminal session: its `.bashrc` starts the
 * helper, and Android lets Termux start it while Termux is on screen. Pipkin then comes back to the front.
 */
internal object TermuxWaker {
  private const val TAG = "Pipkin"
  private const val POLL_MILLIS = 250L

  // From a frozen Termux on the emulator: 4.5 s to the new session, 3.4 s for `am startservice`, 6 s for Node.
  private const val WAIT_MILLIS = 30_000L
  private const val RETURN_GRACE_MILLIS = 1_000L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Mutex()
  private var waking: Deferred<Boolean>? = null

  /**
   * A wake that didn't bring the helper up (no key, Node missing, ports taken) would fail the same way on every
   * return to Pipkin and send the user back to Termux each time; until the helper answers again, only the user's
   * own Open Termux tap opens it.
   */
  @Volatile
  private var failed = false

  /**
   * Whether the helper answers, after opening Termux for it if it didn't. [asked] is the user's tap on Open Termux;
   * without it, Termux isn't opened again after a wake that failed. Concurrent callers share one attempt.
   * Termux is only opened while Pipkin is on screen: from the background, Android would block the switch back.
   */
  suspend fun wake(context: Context, asked: Boolean): Boolean {
    val app = context.applicationContext
    val attempt = lock.withLock {
      waking?.takeIf { it.isActive } ?: scope.async { wakeOnce(app, asked) }.also { waking = it }
    }
    return attempt.await()
  }

  /** The helper answered: the next time it doesn't, opening Termux may help again. */
  fun reached() {
    failed = false
  }

  private suspend fun wakeOnce(context: Context, asked: Boolean): Boolean {
    if (HelperBridge.listening(context)) return true
    if (!pipkinStarted() || (failed && !asked)) return false
    // Behind Termux, Android would freeze Pipkin within seconds, long before a cold helper is up.
    val listening = context.appGraph.keepAlive.acquire("waking Termux").use { waitForHelper(context) }
    failed = !listening
    return listening
  }

  private suspend fun waitForHelper(context: Context): Boolean {
    try {
      TermuxInstall.openTermuxNewSession(context)
    } catch (_: ActivityNotFoundException) {
      return false
    }
    val deadline = System.currentTimeMillis() + WAIT_MILLIS
    var listening = false
    while (!listening && System.currentTimeMillis() < deadline) {
      delay(POLL_MILLIS)
      listening = HelperBridge.listening(context)
    }
    Log.i(TAG, "Opened Termux for the helper: listening=$listening")
    bringBack(context, listening)
    return listening
  }

  /**
   * Pipkin's own launcher intent brings its task back. Android may refuse that from the background, so once a running
   * helper sees Pipkin stay behind Termux, it asks Termux, which is on screen, to open Pipkin instead.
   */
  private suspend fun bringBack(context: Context, helperListening: Boolean) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    try {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
      return
    }
    if (!helperListening) return
    // ProcessLifecycleOwner reports a stop 700 ms late; by now, a Pipkin still stopped stayed behind Termux.
    delay(RETURN_GRACE_MILLIS)
    if (!pipkinStarted()) HelperBridge.requestReturn(context)
  }

  private fun pipkinStarted(): Boolean =
    ProcessLifecycleOwner.get().lifecycle.currentStateFlow.value.isAtLeast(Lifecycle.State.STARTED)
}
