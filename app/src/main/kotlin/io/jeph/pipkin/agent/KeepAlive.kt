package io.jeph.pipkin.agent

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reference-counted hold on [AgentService]: the service runs in the foreground with a wake lock
 * while at least one acquisition is open. Held while chats work, during sign-in and during Pi installs.
 */
internal class KeepAlive(context: Context) {
  private val context = context.applicationContext
  private val lock = Any()
  private var holders = 0

  /** Closing the returned handle releases this acquisition; closing it again does nothing. */
  fun acquire(reason: String): AutoCloseable {
    synchronized(lock) {
      holders += 1
      if (holders == 1) update(true, reason)
    }
    val released = AtomicBoolean(false)
    return AutoCloseable {
      if (released.compareAndSet(false, true)) {
        synchronized(lock) {
          holders -= 1
          if (holders == 0) update(false, reason)
        }
      }
    }
  }

  private fun update(active: Boolean, reason: String) {
    if (!AgentService.update(context, active)) {
      Log.w(TAG, "Keep-alive ($reason, active=$active) not applied; Android may stop Pipkin in the background")
    }
  }

  private companion object {
    const val TAG = "Pipkin"
  }
}
