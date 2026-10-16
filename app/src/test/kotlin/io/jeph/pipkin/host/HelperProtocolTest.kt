package io.jeph.pipkin.host

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HelperProtocolTest {
  private val servers = mutableListOf<ServerSocket>()
  private val sockets = mutableListOf<Socket>()

  @After
  fun tearDown() {
    sockets.forEach(Socket::close)
    servers.forEach(ServerSocket::close)
  }

  @Test
  fun handshakeMatchesTheSpecVectors() {
    assertEquals(MAC_S, HelperProtocol.serverMac(KEY, NONCE_C, NONCE_S).toHexString())
    assertEquals(MAC_C, HelperProtocol.clientMac(KEY, NONCE_C, NONCE_S).toHexString())
    val keys = HelperProtocol.sessionKeys(KEY, NONCE_C, NONCE_S)
    assertEquals(C2S, keys.clientToServer.toHexString())
    assertEquals(S2C, keys.serverToClient.toHexString())
  }

  @Test
  fun framesMatchTheSpecVectors() {
    val clientToServer = FrameCipher(C2S.hexToByteArray())
    assertEquals(C2S_FRAME_0, clientToServer.seal(HELLO).toHexString())
    assertEquals(C2S_FRAME_1, clientToServer.seal(HELLO).toHexString())
    val serverToClient = FrameCipher(S2C.hexToByteArray())
    assertEquals(S2C_FRAME_0, serverToClient.seal(S2C_HELLO.toByteArray()).toHexString())
    val opened = FrameCipher(S2C.hexToByteArray()).open(S2C_FRAME_0.hexToByteArray())
    assertEquals(S2C_HELLO, String(opened))
  }

  @Test
  fun authenticatesAndExchangesFrames() {
    val port = fakeHelper(KEY) { session ->
      val request = session.receive()
      session.send(JSONObject().put("type", "hello").put("version", "v").put("echo", request.getString("type")))
    }
    val result = handshake(port, listOf(KEY))

    val connection = (result as HelperProtocol.Handshake.Authenticated).connection
    assertArrayEquals(KEY, result.key)
    connection.send(JSONObject().put("type", "hello"))
    val reply = connection.receive(TIMEOUT)!!
    assertEquals("v", reply.getString("version"))
    assertEquals("hello", reply.getString("echo"))
    connection.close()
  }

  @Test
  fun anotherPairingKeyIsAMismatch() {
    val port = fakeHelper(OTHER_KEY) {}
    assertSame(HelperProtocol.Handshake.KeyMismatch, handshake(port, listOf(KEY)))
  }

  @Test
  fun aReplyWithoutTheMagicIsForeign() {
    val server = listen()
    thread {
      server.accept().use { socket ->
        DataInputStream(socket.getInputStream()).readFully(ByteArray(33))
        socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".padEnd(68).toByteArray())
        socket.getInputStream().read()
      }
    }
    assertSame(HelperProtocol.Handshake.Foreign, handshake(server.localPort, listOf(KEY)))
  }

  @Test
  fun silenceIsForeign() {
    val server = listen()
    thread { server.accept().use { it.getInputStream().readBytes() } }
    assertSame(HelperProtocol.Handshake.Foreign, handshake(server.localPort, listOf(KEY)))
  }

  @Test
  fun thePendingKeyAuthenticatesAndIsPromoted() {
    val port = fakeHelper(OTHER_KEY) {}
    val store = HelperPairing.Store(createTempDirectory().toFile().resolve("pairing.json"))
    store.storePending(KEY)
    store.confirm(KEY)
    store.storePending(OTHER_KEY)
    val result = handshake(port, store.keys().all)

    val authenticated = result as HelperProtocol.Handshake.Authenticated
    assertArrayEquals(OTHER_KEY, authenticated.key)
    assertTrue(store.confirm(authenticated.key))
    assertArrayEquals(OTHER_KEY, store.keys().current)
    assertNull(store.keys().pending)
    authenticated.connection.close()
  }

  @Test
  fun aTamperedFrameIsRejected() {
    val port = fakeHelper(KEY) { session ->
      val frame = session.seal(JSONObject().put("type", "started").put("pid", 1))
      frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 1).toByte()
      session.writeRaw(frame)
    }
    val connection = (handshake(port, listOf(KEY)) as HelperProtocol.Handshake.Authenticated).connection
    assertFailsWithIo { connection.receive(TIMEOUT) }
    connection.close()
  }

  @Test
  fun aReplayedFrameIsRejected() {
    val port = fakeHelper(KEY) { session ->
      val frame = session.seal(JSONObject().put("type", "started").put("pid", 1))
      session.writeRaw(frame)
      session.writeRaw(frame)
    }
    val connection = (handshake(port, listOf(KEY)) as HelperProtocol.Handshake.Authenticated).connection
    assertEquals("started", connection.receive(TIMEOUT)!!.getString("type"))
    assertFailsWithIo { connection.receive(TIMEOUT) }
    connection.close()
  }

  @Test
  fun anOversizedFrameLengthIsRejected() {
    val port = fakeHelper(KEY) { session ->
      session.output.write(ByteBuffer.allocate(4).putInt(HelperProtocol.MAX_FRAME_BYTES + 1).array())
      session.output.flush()
    }
    val connection = (handshake(port, listOf(KEY)) as HelperProtocol.Handshake.Authenticated).connection
    assertFailsWithIo { connection.receive(TIMEOUT) }
    connection.close()
  }

  @Test
  fun reachPrefersTheHelperAndClassifiesTheRest() {
    val closed = ServerSocket(0, 1, LOOPBACK).run {
      close()
      localPort
    }
    val reach = HelperBridge.reach(listOf(KEY), listOf(closed, fakeHelper(KEY) {}))
    (reach as HelperBridge.Reach.Connected).connection.close()

    assertSame(HelperBridge.Reach.Rejected, HelperBridge.reach(listOf(OTHER_KEY), listOf(closed, fakeHelper(KEY) {})))
    assertSame(HelperBridge.Reach.Unreachable, HelperBridge.reach(listOf(KEY), listOf(closed)))
    val silent = listen()
    thread { silent.accept().use { it.getInputStream().readBytes() } }
    assertSame(HelperBridge.Reach.PortsTaken, HelperBridge.reach(listOf(KEY), listOf(silent.localPort)))
  }

  private fun handshake(port: Int, keys: List<ByteArray>): HelperProtocol.Handshake {
    val socket = Socket(LOOPBACK, port).also { sockets += it }
    return HelperProtocol.handshake(socket, keys)
  }

  private fun listen() = ServerSocket(0, 1, LOOPBACK).also { servers += it }

  /** A helper holding [key] that runs [body] after a successful handshake, then waits for the client to close. */
  private fun fakeHelper(key: ByteArray, body: (FakeSession) -> Unit): Int {
    val server = listen()
    thread {
      server.accept().use { socket ->
        val input = DataInputStream(socket.getInputStream())
        val opening = ByteArray(33).also(input::readFully)
        check(opening[0] == 1.toByte())
        val nonceC = opening.copyOfRange(1, 33)
        val output = socket.getOutputStream()
        output.write("PKH1".toByteArray() + NONCE_S + HelperProtocol.serverMac(key, nonceC, NONCE_S))
        output.flush()
        val macC = ByteArray(32)
        try {
          input.readFully(macC)
        } catch (_: IOException) {
          return@use
        }
        if (!MessageDigest.isEqual(macC, HelperProtocol.clientMac(key, nonceC, NONCE_S))) return@use
        body(FakeSession(input, output, HelperProtocol.sessionKeys(key, nonceC, NONCE_S)))
        input.read()
      }
    }
    return server.localPort
  }

  private class FakeSession(
    private val input: DataInputStream,
    val output: OutputStream,
    keys: HelperProtocol.SessionKeys,
  ) {
    private val opener = FrameCipher(keys.clientToServer)
    private val sealer = FrameCipher(keys.serverToClient)

    fun receive(): JSONObject {
      val frame = ByteArray(input.readInt()).also(input::readFully)
      return JSONObject(String(opener.open(frame)))
    }

    fun seal(message: JSONObject): ByteArray = sealer.seal(message.toString().toByteArray())

    fun send(message: JSONObject) = writeRaw(seal(message))

    fun writeRaw(frame: ByteArray) {
      output.write(ByteBuffer.allocate(4).putInt(frame.size).array())
      output.write(frame)
      output.flush()
    }
  }

  private fun assertFailsWithIo(block: () -> Unit) {
    try {
      block()
      fail("Expected an IOException")
    } catch (_: IOException) {
      // Expected.
    }
  }

  private companion object {
    const val TIMEOUT = 5_000
    val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    val KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToByteArray()
    val OTHER_KEY = ByteArray(32) { 7 }
    val NONCE_C = ByteArray(32) { 0xaa.toByte() }
    val NONCE_S = ByteArray(32) { 0xbb.toByte() }
    val HELLO = """{"type":"hello"}""".toByteArray()
    const val S2C_HELLO = """{"type":"hello","version":"x"}"""
    const val MAC_S = "c92c57837f8549ddfb5a965ace997642ce25f3e3b136a6bf561433663f3a1461"
    const val MAC_C = "9fb54e8cc24ecc55dea53d1287d19d3bcfb851c899430f38410a4df7a93503ab"
    const val C2S = "eb0b435f5cf0a7eb2819992de866100bbfc6a262e919e47e01853e37d871fab7"
    const val S2C = "3a4ec171786f7ad7f37d65dfa5744436aab39444b5c7823744a1225b0e36f487"
    const val C2S_FRAME_0 = "d63a72ad9216ab54d318f274debda5af6abd7a1a6216a23ca62b049800c61723"
    const val C2S_FRAME_1 = "740b781b9f3a35201158d88e92de83752160aa8dfd430a4c5ae8f2520ca20250"
    const val S2C_FRAME_0 =
      "a916a91721dc194149655fc1fa133d8a8a5746ae372aaa1199edd92cc04ab9340a0d56b15a8f2a0ba991c0bf6156"
  }
}
