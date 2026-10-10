package io.jeph.pipkin.host

import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HostServerTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val server = HostServer(scope)
  private val clients = mutableListOf<Socket>()

  @After
  fun tearDown() {
    clients.forEach(Socket::close)
    server.close()
    scope.cancel()
  }

  @Test
  fun validTokenCompletesItsOwnDeferred() = runBlocking {
    val mine = server.register(TOKEN)
    val other = server.register(OTHER_TOKEN)
    connect().getOutputStream().write(hello(TOKEN).toByteArray())

    val channel = withTimeout(TIMEOUT) { mine.await() }

    assertEquals(TOKEN, channel.hello.getString("token"))
    assertEquals("rpc", channel.hello.getString("mode"))
    assertFalse(other.isCompleted)
    channel.close()
  }

  @Test
  fun wrongTokenIsClosedWithoutCompleting() {
    val deferred = server.register(TOKEN)
    val client = connect()
    client.getOutputStream().write(hello(OTHER_TOKEN).toByteArray())

    assertEquals(-1, client.getInputStream().read())
    assertFalse(deferred.isCompleted)
  }

  @Test
  fun wrongProtocolIsClosedWithoutCompleting() {
    val deferred = server.register(TOKEN)
    val client = connect()
    client.getOutputStream().write(hello(TOKEN, protocol = 2).toByteArray())

    assertEquals(-1, client.getInputStream().read())
    assertFalse(deferred.isCompleted)
  }

  @Test
  fun tokenIsSingleUse() = runBlocking {
    val deferred = server.register(TOKEN)
    connect().getOutputStream().write(hello(TOKEN).toByteArray())
    val channel = withTimeout(TIMEOUT) { deferred.await() }

    val replay = connect()
    replay.getOutputStream().write(hello(TOKEN).toByteArray())

    assertEquals(-1, replay.getInputStream().read())
    channel.close()
  }

  private fun connect(): Socket = Socket(InetAddress.getByName("127.0.0.1"), server.port).also {
    it.soTimeout = TIMEOUT.toInt()
    clients += it
  }

  private fun hello(token: String, protocol: Int = HostProtocol.VERSION): String = JSONObject()
    .put("type", HostProtocol.HELLO)
    .put("protocol", protocol)
    .put("token", token)
    .put("mode", "rpc")
    .toString() + "\n"

  private companion object {
    const val TIMEOUT = 5_000L
    val TOKEN = "a".repeat(64)
    val OTHER_TOKEN = "b".repeat(64)
  }
}
