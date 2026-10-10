package io.jeph.pipkin.host

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HostChannelTest {
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
  fun lineSeparatorInsideStringSurvivesSplitChunks() = runBlocking {
    val (client, channel) = open()
    val first = "{\"type\":\"x\",\"s\":\"a\u2028b\u2029c\"}\n".toByteArray()
    val second = "{\"type\":\"y\"}\r\n".toByteArray()
    val bytes = first + second
    val output = client.getOutputStream()
    // Split inside the three-byte UTF-8 encoding of U+2028 and inside the second line.
    val cuts = listOf(0, 19, first.size + 4, bytes.size)
    for ((from, to) in cuts.zipWithNext()) {
      output.write(bytes, from, to - from)
      output.flush()
      delay(CHUNK_PAUSE_MILLIS)
    }

    val lines = withTimeout(TIMEOUT) { channel.lines.take(2).toList() }

    assertEquals("a\u2028b\u2029c", lines[0].getString("s"))
    assertEquals("y", lines[1].getString("type"))
    channel.close()
  }

  @Test
  fun sendWritesOneLineAndEofCompletesClosed() = runBlocking {
    val (client, channel) = open()

    channel.send(JSONObject().put("type", "z").put("s", "\u2028"))
    val reader = LineReader(client.getInputStream())
    val echoed = JSONObject(checkNotNull(reader.readLine(HostProtocol.MAX_LINE_BYTES)))
    client.close()

    assertEquals("\u2028", echoed.getString("s"))
    withTimeout(TIMEOUT) { channel.closed.await() }
  }

  @Test
  fun readerSplitsOnLfOnlyAcrossSingleByteReads() {
    val text = "{\"s\":\"a\rb\u2028\"}\r\n{}\npartial"
    val reader = LineReader(OneByteAtATime(text.toByteArray()))

    assertEquals("{\"s\":\"a\rb\u2028\"}", reader.readLine(HostProtocol.MAX_LINE_BYTES))
    assertEquals("{}", reader.readLine(HostProtocol.MAX_LINE_BYTES))
    assertNull(reader.readLine(HostProtocol.MAX_LINE_BYTES))
  }

  @Test
  fun readerRejectsOversizedLines() {
    val reader = LineReader(ByteArrayInputStream("x".repeat(65).toByteArray() + '\n'.code.toByte()))

    assertThrows(IOException::class.java) { reader.readLine(64) }
  }

  private suspend fun open(): Pair<Socket, HostChannel> {
    val deferred = server.register(TOKEN)
    val client = Socket(InetAddress.getByName("127.0.0.1"), server.port).also {
      it.soTimeout = TIMEOUT.toInt()
      clients += it
    }
    val hello = JSONObject()
      .put("type", HostProtocol.HELLO)
      .put("protocol", HostProtocol.VERSION)
      .put("token", TOKEN)
      .put("mode", "rpc")
    client.getOutputStream().write((hello.toString() + "\n").toByteArray())
    return client to withTimeout(TIMEOUT) { deferred.await() }
  }

  private class OneByteAtATime(private val bytes: ByteArray) : InputStream() {
    private var position = 0

    override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      val next = read()
      if (next < 0) return -1
      buffer[offset] = next.toByte()
      return 1
    }
  }

  private companion object {
    const val TIMEOUT = 5_000L
    const val CHUNK_PAUSE_MILLIS = 50L
    val TOKEN = "c".repeat(64)
  }
}
