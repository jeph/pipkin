package io.jeph.pipkin.host

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedHostTest {
  /** The `host.mjs` end of one host the [SharedHost] opened. */
  private class Host(val socket: Socket) {
    private val reader = LineReader(socket.getInputStream())

    /** The next line the app sent, or null once the app closed the connection. */
    fun read(): JSONObject? = reader.readLine(MAX_LINE)?.let(::JSONObject)

    fun answer(request: JSONObject) {
      val reply = JSONObject().put("id", request.getLong("id")).put("answer", request.getString("op"))
      socket.getOutputStream().write("$reply\n".toByteArray())
    }
  }

  private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val server = HostServer(io)
  private val confined = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

  /** SharedHost is main-thread confined; this single thread stands in for the main thread. */
  private val scope = CoroutineScope(SupervisorJob() + confined)
  private val opened = LinkedBlockingQueue<Host>()
  private val all = CopyOnWriteArrayList<Host>()
  private var nextToken = 0
  private val shared = SharedHost(scope, IDLE_MILLIS) { open() }

  @After
  fun tearDown() {
    all.forEach { it.socket.close() }
    server.close()
    scope.cancel()
    io.cancel()
    confined.close()
  }

  @Test
  fun retiringAnswersTheRequestInFlightThenEndsTheHostAndTheNextRequestOpensAnother() = runBlocking {
    val first = scope.async { shared.request(JSONObject().put("op", "first"), TIMEOUT) }
    val firstHost = nextHost()
    val firstRequest = withContext(Dispatchers.IO) { checkNotNull(firstHost.read()) }

    withContext(confined) { shared.retire() }
    withContext(Dispatchers.IO) { firstHost.answer(firstRequest) }

    assertEquals("first", withTimeout(TIMEOUT) { first.await() }.getString("answer"))
    assertNull(withContext(Dispatchers.IO) { firstHost.read() })

    val second = scope.async { shared.request(JSONObject().put("op", "second"), TIMEOUT) }
    val secondHost = nextHost()
    withContext(Dispatchers.IO) { secondHost.answer(checkNotNull(secondHost.read())) }
    assertEquals("second", withTimeout(TIMEOUT) { second.await() }.getString("answer"))
  }

  private suspend fun nextHost(): Host =
    withContext(Dispatchers.IO) { checkNotNull(opened.poll(TIMEOUT, TimeUnit.MILLISECONDS)) { "no host opened" } }

  /** Connects a host the way `host.mjs` does and returns the app's end of it. */
  private suspend fun open(): HostChannel {
    val token = (++nextToken).toString().padStart(TOKEN_LENGTH, '0')
    val connected = server.register(token)
    val host = withContext(Dispatchers.IO) {
      val socket = Socket(InetAddress.getByName("127.0.0.1"), server.port).also { it.soTimeout = TIMEOUT.toInt() }
      val hello = JSONObject()
        .put("type", HostProtocol.HELLO)
        .put("protocol", HostProtocol.VERSION)
        .put("token", token)
        .put("mode", "utility")
      socket.getOutputStream().write("$hello\n".toByteArray())
      Host(socket)
    }
    all += host
    val channel = withTimeout(TIMEOUT) { connected.await() }
    opened.put(host)
    return channel
  }

  private companion object {
    const val TIMEOUT = 5_000L
    const val IDLE_MILLIS = 60_000L
    const val MAX_LINE = 1_024
    const val TOKEN_LENGTH = 64
  }
}
