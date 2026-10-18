package io.jeph.pipkin.agent

import io.jeph.pipkin.AppStrings
import io.jeph.pipkin.R
import io.jeph.pipkin.host.HostRejected
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * A Pi RPC command failed for [reason]. [message] is user-presentable: Pi's own [piError] when it rejected the command
 * with one, else Pipkin's explanation in the app's language when it is read.
 */
internal class RpcError(
  val command: String,
  val reason: Reason,
  private val piError: String = "",
  cause: Throwable? = null,
) : Exception(null, cause) {
  enum class Reason {
    /** Pi answered `success:false`. */
    Rejected,
    TimedOut,
    NotRunning,

    /** The host refused to run Pi; the [HostRejected] cause says why. */
    HostRefused,
  }

  val timedOut: Boolean get() = reason == Reason.TimedOut

  override val message: String get() = when (reason) {
    Reason.Rejected -> piError.ifEmpty { AppStrings.get(R.string.agent_rpc_rejected, command) }
    Reason.TimedOut -> AppStrings.get(R.string.agent_rpc_timed_out, command)
    Reason.NotRunning -> AppStrings.get(R.string.agent_not_running)
    Reason.HostRefused -> cause?.message ?: AppStrings.get(R.string.agent_not_running)
  }
}

/**
 * Correlates Pi RPC commands with their `response` records. Ids are `r<counter>`; every caller
 * waits at most [timeoutMillis]. Safe to use from any thread.
 */
internal class RpcClient(
  private val send: suspend (JSONObject) -> Unit,
  private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
  private class Pending(val command: String, val reply: CompletableDeferred<JSONObject>)

  private class Closed(val refusal: HostRejected?)

  private val counter = AtomicLong()
  private val pending = ConcurrentHashMap<String, Pending>()
  private val closed = AtomicReference<Closed?>(null)

  /**
   * Sends `{type: command, id, ...params}` and returns the response's `data` (empty when absent).
   * A null [timeoutMillis] waits until Pi answers or the connection closes.
   */
  suspend fun call(
    command: String,
    params: JSONObject = JSONObject(),
    timeoutMillis: Long? = this.timeoutMillis,
  ): JSONObject {
    val id = "r${counter.incrementAndGet()}"
    val reply = CompletableDeferred<JSONObject>()
    pending[id] = Pending(command, reply)
    try {
      // Checked after registering, so a concurrent close either sees this call or this call sees it.
      closed.get()?.let { throw it.error(command) }
      try {
        send(params.put("type", command).put("id", id))
      } catch (error: IOException) {
        throw RpcError(command, RpcError.Reason.NotRunning, cause = error)
      }
      val response = if (timeoutMillis == null) {
        reply.await()
      } else {
        withTimeoutOrNull(timeoutMillis) { reply.await() } ?: throw RpcError(command, RpcError.Reason.TimedOut)
      }
      if (!response.optBoolean("success")) {
        throw RpcError(command, RpcError.Reason.Rejected, piError = response.optString("error"))
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
   * Fails every waiting and later caller with an [RpcError]: [RpcError.Reason.HostRefused] for a host [refusal], else
   * [RpcError.Reason.NotRunning]. The first close wins, so a host refusal is not masked by the disconnect that follows.
   */
  fun close(refusal: HostRejected? = null) {
    closed.compareAndSet(null, Closed(refusal))
    val first = closed.get() ?: return
    for (id in pending.keys.toList()) {
      val entry = pending.remove(id) ?: continue
      entry.reply.completeExceptionally(first.error(entry.command))
    }
  }

  private fun Closed.error(command: String): RpcError = if (refusal == null) {
    RpcError(command, RpcError.Reason.NotRunning)
  } else {
    RpcError(command, RpcError.Reason.HostRefused, cause = refusal)
  }

  companion object {
    const val TIMEOUT_MILLIS = 30_000L
  }
}
