package io.jeph.pipkin.termux

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/** One pinned TLS WebSocket session. Receipts and RPC correlation share UUID request IDs. */
internal class TermuxConnection private constructor(private val client: OkHttpClient) {
  private val lock = Any()
  private val opened = CompletableDeferred<Unit>()
  private val pending = HashMap<String, CompletableDeferred<JSONObject>>()
  private val listeners = LinkedHashSet<Pair<(JSONObject) -> Unit, (RuntimeFailure) -> Unit>>()
  private var socket: WebSocket? = null
  private var failure: RuntimeFailure? = null
  private var upgraded = false

  lateinit var instance: String
    private set

  private val listener = object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      if (response.header("Sec-WebSocket-Protocol") != RPC_SUBPROTOCOL) {
        fail(authenticationFailure())
        return
      }
      synchronized(lock) {
        if (failure != null) return
        upgraded = true
        opened.complete(Unit)
      }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      try {
        if (text.length > WIRE_LIMIT) protocolFailure()
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > WIRE_LIMIT) protocolFailure()
        dispatch(strictObject(bytes))
      } catch (error: RuntimeFailure) {
        fail(error)
      }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
      fail(RuntimeFailure(RuntimeFailureKind.Compatibility, "The supervisor sent a binary RPC message."))
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      fail(RuntimeFailure(RuntimeFailureKind.Transport, "The supervisor connection closed."))
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      fail(RuntimeFailure(RuntimeFailureKind.Transport, "The supervisor connection closed."))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      val connected = synchronized(lock) { upgraded }
      val error = when {
        !connected && refusedConnection(t) -> RuntimeFailure(RuntimeFailureKind.Endpoint, "refused", t)

        connected -> RuntimeFailure(RuntimeFailureKind.Transport, "The supervisor connection failed.", t)

        t is SocketTimeoutException || t is ConnectException ->
          RuntimeFailure(RuntimeFailureKind.Transport, "Could not connect to the supervisor.", t)

        else -> authenticationFailure(t)
      }
      fail(error)
    }
  }

  private fun dispatch(record: JSONObject) {
    if (record.opt("jsonrpc") != "2.0") protocolFailure()
    if (record.has("id")) {
      if (record.has("method")) protocolFailure()
      val id = record.string("id")
      if (!validUuid(id)) protocolFailure()
      // Validate before removing the waiter: a malformed response must fail it, not strand it.
      val result = try {
        rpcResult(record)
      } catch (error: RuntimeRejection) {
        synchronized(lock) { pending.remove(id) }?.completeExceptionally(error)
        return
      } catch (error: RuntimeFailure) {
        if (error.kind != RuntimeFailureKind.Transport) throw error
        synchronized(lock) { pending.remove(id) }?.completeExceptionally(error)
        return
      }
      synchronized(lock) { pending.remove(id) }?.complete(result)
      return
    }
    if (record.opt("method") != RPC_EVENT_METHOD || record.has("result") || record.has("error")) protocolFailure()
    val event = record.optJSONObject("params") ?: protocolFailure()
    if (event.opt("event") == "superseded") {
      fail(RuntimeFailure(RuntimeFailureKind.Transport, "Another Pipkin instance took over the supervisor connection."))
      return
    }
    val callbacks = synchronized(lock) { if (failure == null) listeners.map { it.first } else emptyList() }
    for (callback in callbacks) callback(event)
  }

  fun subscribe(onEvent: (JSONObject) -> Unit, onFailure: (RuntimeFailure) -> Unit): AutoCloseable {
    val entry = onEvent to onFailure
    val existing = synchronized(lock) {
      failure.also { if (it == null) listeners.add(entry) }
    }
    existing?.let(onFailure)
    return AutoCloseable { synchronized(lock) { listeners.remove(entry) } }
  }

  suspend fun request(
    method: String,
    params: JSONObject,
    timeoutMillis: Long,
    requestId: String = UUID.randomUUID().toString(),
  ): JSONObject {
    require(validUuid(requestId)) { "RPC request ID must be a UUID" }
    val text = JSONObject().put("jsonrpc", "2.0").put("id", requestId).put("method", method).put("params", params)
      .toString()
    if (text.toByteArray(Charsets.UTF_8).size > WIRE_LIMIT) {
      throw RuntimeFailure(RuntimeFailureKind.Compatibility, "Request exceeds the wire limit.")
    }
    val result = CompletableDeferred<JSONObject>()
    try {
      synchronized(lock) {
        failure?.let { throw it }
        check(!pending.containsKey(requestId)) { "RPC request is already pending" }
        pending[requestId] = result
        if (socket?.send(text) != true) {
          throw RuntimeFailure(
            RuntimeFailureKind.Transport,
            "The supervisor could not queue the request; execution is uncertain.",
          )
        }
      }
      return withTimeoutOrNull(timeoutMillis) { result.await() }
        ?: throw RuntimeFailure(RuntimeFailureKind.Transport, "Request '$method' timed out; execution is uncertain.")
    } finally {
      synchronized(lock) { if (pending[requestId] === result) pending.remove(requestId) }
      result.cancel()
    }
  }

  /** Owner close cancels requests without scheduling a reconnect callback. */
  fun close() = fail(RuntimeFailure(RuntimeFailureKind.Transport, "The supervisor connection closed."), notify = false)

  private fun fail(error: RuntimeFailure, notify: Boolean = true) {
    val callbacks: List<(RuntimeFailure) -> Unit>
    synchronized(lock) {
      if (failure != null) return
      failure = error
      opened.completeExceptionally(error)
      for (waiting in pending.values) waiting.completeExceptionally(error)
      pending.clear()
      callbacks = if (notify) listeners.map { it.second } else emptyList()
      listeners.clear()
      socket?.cancel()
    }
    disposal.launch {
      client.connectionPool.evictAll()
      client.dispatcher.executorService.shutdown()
      for (callback in callbacks) callback(error)
    }
  }

  companion object {
    private val disposal = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun open(pairing: TermuxPairing, termuxUid: Int, timeoutMillis: Long): TermuxConnection {
      val connection = TermuxConnection(TermuxTls.client(pairing.identity.certificate, timeoutMillis))
      var handedOff = false
      try {
        val request = Request.Builder()
          .url("wss://127.0.0.1:${pairing.port}/rpc")
          // OkHttp writes HTTP headers only after TLS trust, hostname and pin verification.
          .header("Authorization", "Bearer ${pairing.token}")
          .header("Sec-WebSocket-Protocol", RPC_SUBPROTOCOL)
          .build()
        val socket = connection.client.newWebSocket(request, connection.listener)
        synchronized(connection.lock) {
          connection.socket = socket
          if (connection.failure != null) socket.cancel()
        }
        withTimeoutOrNull(timeoutMillis) { connection.opened.await() }
          ?: throw RuntimeFailure(RuntimeFailureKind.Transport, "Timed out connecting to the supervisor.")
        val status = try {
          connection.request("status", JSONObject(), timeoutMillis)
        } catch (error: RuntimeRejection) {
          throw RuntimeFailure(RuntimeFailureKind.Compatibility, "The supervisor refused its status request.", error)
        }
        if (!validUuid(status.string("instance")) || status.integer("uid") != termuxUid.toLong()) protocolFailure()
        connection.instance = status.string("instance")
        handedOff = true
        return connection
      } finally {
        if (!handedOff) connection.close()
      }
    }

    private fun authenticationFailure(cause: Throwable? = null) = RuntimeFailure(
      RuntimeFailureKind.Authentication,
      "The paired TLS supervisor could not be authenticated. Stop any old supervisor in Termux, then choose Re-pair. " +
        "Re-pair alone cannot bypass an old supervisor's data-root lock.",
      cause,
    )

    /** A timeout, unreachable network, TLS failure or HTTP rejection never authorizes a launch. */
    private fun refusedConnection(error: Throwable): Boolean {
      var current: Throwable? = error
      while (current != null) {
        if (current is ConnectException &&
          (
            current.message.orEmpty().contains("ECONNREFUSED") ||
              current.message.orEmpty().contains("Connection refused", ignoreCase = true)
            )
        ) {
          return true
        }
        current = current.cause?.takeUnless { it === current }
      }
      return false
    }
  }
}
