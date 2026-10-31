package io.jeph.pipkin.data

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether idle chats need a reload after a write to Pi's configuration that reports whether it changed anything. A
 * write that throws or is cancelled may have changed the configuration before its answer was lost, and a retry then
 * finds nothing left to change, so the next write to finish asks for the reload the lost one owed.
 */
internal class PendingReload {
  private val owed = AtomicBoolean(false)

  /** Runs [write], which answers whether it changed anything; true when it did or an earlier write didn't finish. */
  suspend fun after(write: suspend () -> Boolean): Boolean {
    val earlierUnfinished = owed.getAndSet(true)
    val changed = write()
    owed.set(false)
    return changed || earlierUnfinished
  }
}
