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
import org.junit.Assert.assertTrue
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

  @Test
  fun getRequestsAreAnsweredBySetupAndClosed() {
    val session = SetupSession(
      sid = SID,
      token = ByteArray(32),
      key = ByteArray(32),
      expiresAt = Long.MAX_VALUE,
      files = mapOf("setup.sh" to "installer".toByteArray(), "run.sh" to "run script".toByteArray()),
      onKeyServed = {},
    )
    PlaySetup.open(session)

    val ok = get("/pipkin/$SID/run.sh")
    assertTrue(ok.startsWith("HTTP/1.1 200 OK\r\n"))
    assertTrue("Content-Length: 10\r\n" in ok)
    assertTrue("Connection: close\r\n" in ok)
    assertTrue("Cache-Control: no-store\r\n" in ok)
    assertTrue(ok.endsWith("\r\n\r\nrun script"))
    assertTrue(get("/pipkin/$SID/nothing").startsWith("HTTP/1.1 404 "))
    session.complete()
    assertTrue(get("/pipkin/$SID/run.sh").startsWith("HTTP/1.1 410 "))
  }

  /** The whole response; the server closes the connection after it. */
  private fun get(path: String): String {
    val client = connect()
    client.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nUser-Agent: curl\r\n\r\n".toByteArray())
    return String(client.getInputStream().readBytes())
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
    const val SID = "00112233445566778899aabbccddeeff"
  }
}
