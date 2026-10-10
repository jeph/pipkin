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
        scope.launch { handshake(socket) }
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

  private fun handshake(socket: Socket) {
    val reader = LineReader(socket.getInputStream())
    val hello = try {
      socket.soTimeout = HELLO_TIMEOUT_MILLIS
      reader.readLine(HostProtocol.MAX_HELLO_BYTES)?.let { JSONObject(it) }
    } catch (_: IOException) {
      null
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

  /** Compares against every registered token in constant time per comparison. */
  private fun claim(token: String): CompletableDeferred<HostChannel>? {
    val presented = token.toByteArray(Charsets.UTF_8)
    val match = pending.keys.firstOrNull { MessageDigest.isEqual(it.toByteArray(Charsets.UTF_8), presented) }
    return match?.let(pending::remove)
  }

  companion object {
    private const val BACKLOG = 50
    private const val HELLO_TIMEOUT_MILLIS = 10_000

    /** Process-wide instance, bound on first use and kept for the process lifetime. */
    val shared: HostServer by lazy { HostServer(CoroutineScope(SupervisorJob() + Dispatchers.IO)) }
  }
}
