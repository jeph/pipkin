package io.jeph.pipkin.localmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What runs models for the [LocalModelScheduler]: at most one loaded at a time. */
internal interface ModelHost {
  /** The model that's loaded and serving, or null. */
  val loadedModel: String?

  /** Unloads whatever is loaded and loads [modelId]; throws when it can't. */
  suspend fun load(modelId: String)

  fun unload()
}

/**
 * Runs requests to local models one at a time, with one model loaded at a time. Requests wait in arrival order, and a
 * request for a model other than the loaded one waits for the running request to end, then the model is swapped.
 *
 * An agent turn is many requests with tool runs between them, so after a chat's request ends the model stays reserved
 * for that chat for [leaseMillis]: its next request runs before others, and nothing swaps the model away meanwhile.
 * Requests for the same model from other chats may still run. The reservation is time-bound, not turn-bound, so a chat
 * waiting on a subagent that uses another model can't deadlock with it; [endLease] ends it early when the turn ends.
 * After [idleUnloadMillis] with nothing to run, the model is unloaded.
 */
internal class LocalModelScheduler(
  private val scope: CoroutineScope,
  private val host: ModelHost,
  private val leaseMillis: Long,
  private val idleUnloadMillis: () -> Long,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private val mutex = Mutex()
  private val waiting = mutableListOf<Waiter>()
  private var running: Waiter? = null
  private var lease: Lease? = null
  private var wakeUp: Job? = null
  private var idleTimer: Job? = null
  private val queueState = MutableStateFlow(QueueSnapshot(emptyList(), null, null))

  /** The requests waiting and running, for the UI. */
  val queue: StateFlow<QueueSnapshot> = queueState.asStateFlow()

  /**
   * Waits for [session]'s turn to use [modelId], loads it if needed, and runs [block]. Cancelling the caller while it
   * waits leaves the queue; while it runs, cancels [block].
   */
  suspend fun <T> run(session: String?, modelId: String, block: suspend () -> T): T {
    val waiter = Waiter(QueuedRequest(session, modelId))
    mutex.withLock {
      waiting += waiter
      idleTimer?.cancel()
      dispatchLocked()
    }
    try {
      waiter.turn.await()
    } catch (cancelled: CancellationException) {
      withContext(NonCancellable) {
        mutex.withLock { if (waiting.remove(waiter)) publishLocked() }
        finish(waiter, served = false)
      }
      throw cancelled
    }
    var served = false
    try {
      if (host.loadedModel != modelId) host.load(modelId)
      served = true
      return block()
    } finally {
      withContext(NonCancellable) { finish(waiter, served) }
    }
  }

  /** Ends [session]'s reservation, as its agent turn has ended. */
  suspend fun endLease(session: String) {
    mutex.withLock {
      if (lease?.session != session) return
      lease = null
      dispatchLocked()
    }
  }

  /** Frees the slot; a request that got its model leaves a reservation behind, a failed load doesn't. */
  private suspend fun finish(waiter: Waiter, served: Boolean) {
    mutex.withLock {
      if (running !== waiter) return
      running = null
      val session = waiter.request.session
      lease = if (served && session != null) Lease(session, waiter.request.modelId, now() + leaseMillis) else null
      dispatchLocked()
    }
  }

  private fun dispatchLocked() {
    wakeUp?.cancel()
    wakeUp = null
    if (running == null) {
      lease = lease?.takeIf { it.expiresAt > now() }
      val index = pickNext(waiting.map { it.request }, lease)
      val held = lease
      when {
        index != null -> {
          running = waiting.removeAt(index).also { it.turn.complete(Unit) }
        }

        held != null && waiting.isNotEmpty() -> wakeUp = scope.launch {
          delay(held.expiresAt - now())
          mutex.withLock { dispatchLocked() }
        }

        waiting.isEmpty() -> scheduleIdleUnloadLocked()
      }
    }
    publishLocked()
  }

  private fun scheduleIdleUnloadLocked() {
    idleTimer?.cancel()
    idleTimer = scope.launch {
      delay(idleUnloadMillis())
      mutex.withLock { if (running == null && waiting.isEmpty()) host.unload() }
    }
  }

  private fun publishLocked() {
    queueState.value = QueueSnapshot(waiting.map { it.request }, running?.request, lease?.modelId)
  }

  private class Waiter(val request: QueuedRequest) {
    val turn = CompletableDeferred<Unit>()
  }

  /** A chat's reservation of [modelId] until [expiresAt]. */
  internal data class Lease(val session: String, val modelId: String, val expiresAt: Long)

  companion object {
    /**
     * The index of the next request to run: the reserving chat's first, else the first for the reserved model; with
     * no reservation, the first in line. Null when everything waiting needs a model the reservation holds off.
     */
    internal fun pickNext(waiting: List<QueuedRequest>, lease: Lease?): Int? {
      if (waiting.isEmpty()) return null
      if (lease == null) return 0
      val ownIndex = waiting.indexOfFirst { it.session == lease.session }
      if (ownIndex >= 0) return ownIndex
      return waiting.indexOfFirst { it.modelId == lease.modelId }.takeIf { it >= 0 }
    }
  }
}

/** One request to a local model, by the Pi session it came from (null when Pi didn't say). */
internal data class QueuedRequest(val session: String?, val modelId: String)

/** The scheduler's queue: [waiting] in order, the one [running], and the model a chat's reservation holds. */
internal data class QueueSnapshot(
  val waiting: List<QueuedRequest>,
  val running: QueuedRequest?,
  val reservedModel: String?,
)
