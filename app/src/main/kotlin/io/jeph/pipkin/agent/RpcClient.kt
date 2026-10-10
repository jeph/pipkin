package io.jeph.pipkin.agent

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** A Pi RPC command failed: Pi answered `success:false`, did not answer in time, or is not running. */
internal class RpcError(val command: String, val error: String, cause: Throwable? = null) : Exception(error, cause)

/**
 * Correlates Pi RPC commands with their `response` records. Ids are `r<counter>`; every caller
 * waits at most [timeoutMillis]. Safe to use from any thread.
 */
internal class RpcClient(
  private val send: suspend (JSONObject) -> Unit,
  private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
  private class Pending(val command: String, val reply: CompletableDeferred<JSONObject>)

  private class Closed(val message: String, val cause: Throwable?)

  private val counter = AtomicLong()
  private val pending = ConcurrentHashMap<String, Pending>()
  private val closed = AtomicReference<Closed?>(null)

  /** Sends `{type: command, id, ...params}` and returns the response's `data` (empty when absent). */
  suspend fun call(command: String, params: JSONObject = JSONObject()): JSONObject {
    val id = "r${counter.incrementAndGet()}"
    val reply = CompletableDeferred<JSONObject>()
    pending[id] = Pending(command, reply)
    try {
      // Checked after registering, so a concurrent close either sees this call or this call sees it.
      closed.get()?.let { throw RpcError(command, it.message, it.cause) }
      try {
        send(params.put("type", command).put("id", id))
      } catch (error: IOException) {
        throw RpcError(command, NOT_RUNNING, error)
      }
      val response = withTimeoutOrNull(timeoutMillis) { reply.await() }
        ?: throw RpcError(command, "Pi did not answer $command in time.")
      if (!response.optBoolean("success")) {
        throw RpcError(command, response.optString("error").ifEmpty { "Pi rejected $command." })
      }
      return response.optJSONObject("data") ?: JSONObject()
    } finally {
      pending.remove(id)
    }
  }

  /** Resolves the caller waiting for this `type:"response"` record; unknown ids are dropped. */
  fun onResponse(response: JSONObject) {
    val id = response.opt("id") as? String ?: return
    pending.remove(id)?.reply?.complete(response)
  }

  /**
   * Fails every waiting and later caller with an [RpcError]. The first close's [message] and [cause]
   * win, so a host refusal is not masked by the disconnect that follows it.
   */
  fun close(message: String, cause: Throwable? = null) {
    closed.compareAndSet(null, Closed(message, cause))
    val reason = closed.get() ?: return
    for (id in pending.keys.toList()) {
      val entry = pending.remove(id) ?: continue
      entry.reply.completeExceptionally(RpcError(entry.command, reason.message, reason.cause))
    }
  }

  companion object {
    const val TIMEOUT_MILLIS = 30_000L
    const val NOT_RUNNING = "The agent is not running."
  }
}
