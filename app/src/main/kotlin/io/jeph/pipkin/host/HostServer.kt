package io.jeph.pipkin.host

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/**
 * Loopback listener every `host.mjs` process connects back to. Any app on the device can reach
 * 127.0.0.1, so a connection is only routed after its hello presents a registered single-use token.
 * A connection opening with `GET ` is a setup download from Termux instead, answered by [PlaySetup].
 */
internal class HostServer(private val scope: CoroutineScope) : AutoCloseable {
  private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
  private val pending = ConcurrentHashMap<String, CompletableDeferred<HostChannel>>()

  val port: Int get() = server.localPort

  init {
    scope.launch {
      while (!server.isClosed) {
        val socket = try {
          server.accept()
        } catch (_: IOException) {
          break
        }
        scope.launch { accept(socket) }
      }
    }
  }

  /** The deferred completes when a host presents [token]; a token is consumed by its first match. */
  fun register(token: String): CompletableDeferred<HostChannel> {
    val deferred = CompletableDeferred<HostChannel>()
    check(pending.putIfAbsent(token, deferred) == null) { "Token already registered" }
    return deferred
  }

  fun unregister(token: String) {
    pending -= token
  }

  override fun close() {
    server.close()
  }

  private fun accept(socket: Socket) {
    val reader = LineReader(socket.getInputStream())
    val firstLine = try {
      socket.soTimeout = HELLO_TIMEOUT_MILLIS
      reader.readLine(HostProtocol.MAX_HELLO_BYTES)
    } catch (_: IOException) {
      null
    }
    when {
      firstLine == null -> socket.close()
      firstLine.startsWith("GET ") -> serveSetup(socket, reader, firstLine)
      else -> handshake(socket, reader, firstLine)
    }
  }

  private fun handshake(socket: Socket, reader: LineReader, firstLine: String) {
    val hello = try {
      JSONObject(firstLine)
    } catch (_: JSONException) {
      null
    }
    val deferred = hello?.takeIf {
      it.optString("type") == HostProtocol.HELLO && it.optInt("protocol", -1) == HostProtocol.VERSION
    }?.let { claim(it.optString("token")) }
    if (hello == null || deferred == null) {
      socket.close()
      return
    }
    val channel = try {
      socket.soTimeout = 0
      HostChannel(socket, reader, hello, scope)
    } catch (_: IOException) {
      socket.close()
      return
    }
    // The launcher gave up (timeout or cancellation) between the match and now.
    if (!deferred.complete(channel)) channel.close()
  }

  /** One request per connection: the headers are read and ignored, then the answer closes the connection. */
  private fun serveSetup(socket: Socket, reader: LineReader, requestLine: String) = socket.use {
    try {
      socket.soTimeout = HTTP_TIMEOUT_MILLIS
      var budget = MAX_HTTP_HEADER_BYTES
      while (true) {
        val header = reader.readLine(budget) ?: return@use
        if (header.isEmpty()) break
        budget -= header.length + 2
        if (budget <= 0) return@use
      }
      val parts = requestLine.split(' ')
      val target = if (parts.size == 3) parts[1] else ""
      PlaySetup.respond(target).write(socket.getOutputStream())
    } catch (_: IOException) {
      // Slow, oversized or abandoned request; the connection just closes.
    }
  }

  /** Compares against every registered token in constant time per comparison. */
  private fun claim(token: String): CompletableDeferred<HostChannel>? {
    val presented = token.toByteArray(Charsets.UTF_8)
    val match = pending.keys.firstOrNull { MessageDigest.isEqual(it.toByteArray(Charsets.UTF_8), presented) }
    return match?.let(pending::remove)
  }

  companion object {
    private const val BACKLOG = 50
    private const val HELLO_TIMEOUT_MILLIS = 10_000
    private const val HTTP_TIMEOUT_MILLIS = 3_000
    private const val MAX_HTTP_HEADER_BYTES = 16 * 1024

    /** Process-wide instance, bound on first use and kept for the process lifetime. */
    val shared: HostServer by lazy { HostServer(CoroutineScope(SupervisorJob() + Dispatchers.IO)) }
  }
}
