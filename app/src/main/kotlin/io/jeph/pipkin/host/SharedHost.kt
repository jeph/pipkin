package io.jeph.pipkin.host

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * One long-lived request host shared by every caller, so each request doesn't pay for starting Termux. [open] starts
 * the host with the first request; it ends once none has come for [idleMillis]. Requests run concurrently, matched
 * to their replies by id. Main-thread confined.
 */
internal class SharedHost(
  private val scope: CoroutineScope,
  private val idleMillis: Long,
  private val open: suspend () -> HostChannel,
) {
  private val openLock = Mutex()
  private var connection: Connection? = null
  private var idleClose: Job? = null
  private var inFlight = 0
  private var nextId = 0L

  /** Sends [message] with a fresh id and returns the reply carrying that id. */
  suspend fun request(message: JSONObject, timeoutMillis: Long): JSONObject {
    idleClose?.cancel()
    inFlight++
    try {
      return connection().request(message.put("id", ++nextId), timeoutMillis)
    } finally {
      inFlight--
      if (inFlight == 0) {
        idleClose = scope.launch {
          delay(idleMillis)
          connection?.close()
          connection = null
        }
      }
    }
  }

  /**
   * Ends the running host once it has answered the requests already sent to it; the next request starts a new one.
   */
  fun retire() {
    connection?.retire()
    connection = null
  }

  /** The open host, or a new one when there is none or it ended; concurrent first requests share one start. */
  private suspend fun connection(): Connection = openLock.withLock {
    connection?.takeIf { it.alive } ?: Connection(open(), scope).also { connection = it }
  }

  private class Connection(private val channel: HostChannel, scope: CoroutineScope) {
    private val pending = HashMap<Long, CompletableDeferred<JSONObject>>()
    private val reader = scope.launch {
      channel.lines.collect { line -> pending.remove(line.optLong("id", -1))?.complete(line) }
      val failure = HostFailure.Disconnected()
      pending.values.forEach { it.completeExceptionally(failure) }
      pending.clear()
    }

    val alive: Boolean get() = reader.isActive
    private var retired = false

    suspend fun request(message: JSONObject, timeoutMillis: Long): JSONObject {
      val id = message.getLong("id")
      val reply = CompletableDeferred<JSONObject>()
      pending[id] = reply
      try {
        if (!alive) throw HostFailure.Disconnected()
        try {
          channel.send(message)
        } catch (error: IOException) {
          throw HostFailure.Disconnected(error)
        }
        return withTimeoutOrNull(timeoutMillis) { reply.await() } ?: throw HostFailure.Timeout()
      } finally {
        pending.remove(id)
        if (retired && pending.isEmpty()) close()
      }
    }

    fun retire() {
      retired = true
      if (pending.isEmpty()) close()
    }

    fun close() = channel.close()
  }
}
