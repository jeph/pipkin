package io.jeph.pipkin.host

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONException
import org.json.JSONObject

/**
 * Pipkin's side of `pipkin-helper/1`, the protocol of its helper in Termux's Play build: a mutual HMAC handshake over
 * a pairing key, then AES-256-GCM frames keyed per direction and per connection, so a relay on the loopback port can
 * neither read, inject, reorder nor replay them. Pure JVM; the sockets come from the caller.
 */
internal object HelperProtocol {
  /**
   * The helper listens on the first free one, so Pipkin tries them in this order. The release's are 47310–47314 and
   * the debug build's 47320–47324: each build then knocks only on its own helper, whose key it holds.
   */
  val PORTS = List(5) { TermuxBuild.HELPER_FIRST_PORT + it }
  const val KEY_BYTES = 32

  /** The helper replies to the opening at once; anything slower is not Pipkin's helper. */
  const val REPLY_TIMEOUT_MILLIS = 3_000

  const val MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024
  const val TAG_BYTES = 16
  const val MAX_FRAME_BYTES = MAX_PLAINTEXT_BYTES + TAG_BYTES

  private const val VERSION: Byte = 1
  private const val NONCE_BYTES = 32
  private const val MAC_BYTES = 32
  private val MAGIC = "PKH1".toByteArray(Charsets.US_ASCII)
  private val REPLY_BYTES = MAGIC.size + NONCE_BYTES + MAC_BYTES

  private val random = SecureRandom()

  sealed interface Handshake {
    /** [key] is the pairing key the helper proved it holds; [connection] is ready for frames. */
    class Authenticated(val key: ByteArray, val connection: HelperConnection) : Handshake

    /** No `PKH1` reply in time: the port belongs to something else. */
    data object Foreign : Handshake

    /** Pipkin's helper, paired with a key Pipkin doesn't hold. */
    data object KeyMismatch : Handshake
  }

  class SessionKeys(val clientToServer: ByteArray, val serverToClient: ByteArray)

  /**
   * Runs the client side on a connected [socket], verifying the helper's MAC against each of [keys] (current and
   * pending pairing keys). Closes nothing: on [Handshake.Foreign] or [Handshake.KeyMismatch] the caller closes.
   * Throws [IOException] when writing fails.
   */
  fun handshake(socket: Socket, keys: List<ByteArray>, nonceC: ByteArray = randomNonce()): Handshake {
    val output = socket.getOutputStream()
    output.write(byteArrayOf(VERSION) + nonceC)
    output.flush()
    val reply = readReply(socket) ?: return Handshake.Foreign
    if (!MessageDigest.isEqual(reply.copyOfRange(0, MAGIC.size), MAGIC)) return Handshake.Foreign
    val nonceS = reply.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
    val macS = reply.copyOfRange(MAGIC.size + NONCE_BYTES, REPLY_BYTES)
    val key = keys.firstOrNull { MessageDigest.isEqual(serverMac(it, nonceC, nonceS), macS) }
      ?: return Handshake.KeyMismatch
    output.write(clientMac(key, nonceC, nonceS))
    output.flush()
    socket.soTimeout = 0
    return Handshake.Authenticated(key, HelperConnection(socket, sessionKeys(key, nonceC, nonceS)))
  }

  fun serverMac(key: ByteArray, nonceC: ByteArray, nonceS: ByteArray): ByteArray =
    hmac(key, "pipkin-helper-server", nonceC, nonceS)

  fun clientMac(key: ByteArray, nonceC: ByteArray, nonceS: ByteArray): ByteArray =
    hmac(key, "pipkin-helper-client", nonceC, nonceS)

  fun sessionKeys(key: ByteArray, nonceC: ByteArray, nonceS: ByteArray) = SessionKeys(
    clientToServer = hmac(key, "pipkin-helper-c2s", nonceC, nonceS),
    serverToClient = hmac(key, "pipkin-helper-s2c", nonceC, nonceS),
  )

  fun hmac(key: ByteArray, label: String, vararg parts: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.update(label.toByteArray(Charsets.US_ASCII))
    parts.forEach(mac::update)
    return mac.doFinal()
  }

  private fun randomNonce() = ByteArray(NONCE_BYTES).also(random::nextBytes)

  /** All [REPLY_BYTES] within [REPLY_TIMEOUT_MILLIS] in total, or null. */
  private fun readReply(socket: Socket): ByteArray? {
    val input = socket.getInputStream()
    val reply = ByteArray(REPLY_BYTES)
    val deadline = System.nanoTime() + REPLY_TIMEOUT_MILLIS * 1_000_000L
    var read = 0
    while (read < REPLY_BYTES) {
      val leftMillis = (deadline - System.nanoTime()) / 1_000_000L
      if (leftMillis <= 0) return null
      socket.soTimeout = leftMillis.toInt()
      val count = try {
        input.read(reply, read, REPLY_BYTES - read)
      } catch (_: SocketTimeoutException) {
        return null
      }
      if (count < 0) return null
      read += count
    }
    return reply
  }
}

/**
 * AES-256-GCM for one direction of one connection: iv = 4 zero bytes || 8-byte big-endian frame counter, no AAD, the
 * 16-byte tag appended. [seal] and [open] each advance the counter, so a dropped, repeated or reordered frame fails.
 */
internal class FrameCipher(key: ByteArray) {
  private val key = SecretKeySpec(key, "AES")
  private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
  private var counter = 0L

  fun seal(plaintext: ByteArray): ByteArray {
    cipher.init(Cipher.ENCRYPT_MODE, key, nextIv())
    return cipher.doFinal(plaintext)
  }

  /** Throws [IOException] when the frame isn't the next one sealed with this key. */
  fun open(frame: ByteArray): ByteArray = try {
    cipher.init(Cipher.DECRYPT_MODE, key, nextIv())
    cipher.doFinal(frame)
  } catch (error: GeneralSecurityException) {
    throw IOException("Helper frame failed authentication", error)
  }

  private fun nextIv(): GCMParameterSpec {
    val iv = ByteBuffer.allocate(IV_BYTES).putInt(0).putLong(counter++).array()
    return GCMParameterSpec(HelperProtocol.TAG_BYTES * Byte.SIZE_BITS, iv)
  }

  private companion object {
    const val IV_BYTES = 12
  }
}

/**
 * An authenticated connection to the helper carrying one JSON object per frame. One thread may [send] while another
 * [receive]s. Any framing or authentication failure throws [IOException]; the connection is then unusable.
 */
internal class HelperConnection(private val socket: Socket, keys: HelperProtocol.SessionKeys) : AutoCloseable {
  private val output: OutputStream = socket.getOutputStream()
  private val input: InputStream = socket.getInputStream()
  private val sealer = FrameCipher(keys.clientToServer)
  private val opener = FrameCipher(keys.serverToClient)

  @Synchronized
  fun send(message: JSONObject) {
    val plaintext = message.toString().toByteArray(Charsets.UTF_8)
    if (plaintext.size > HelperProtocol.MAX_PLAINTEXT_BYTES) throw IOException("Helper request too large")
    val frame = sealer.seal(plaintext)
    output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(frame.size).array())
    output.write(frame)
    output.flush()
  }

  /** The next message, or null once the helper closed the connection. [timeoutMillis] 0 waits indefinitely. */
  fun receive(timeoutMillis: Int = 0): JSONObject? {
    socket.soTimeout = timeoutMillis
    val header = ByteArray(Int.SIZE_BYTES)
    val first = input.read()
    if (first < 0) return null
    header[0] = first.toByte()
    readFully(header, 1)
    val length = ByteBuffer.wrap(header).int
    if (length < 1 || length > HelperProtocol.MAX_FRAME_BYTES) throw IOException("Bad helper frame length")
    val frame = ByteArray(length)
    readFully(frame, 0)
    val plaintext = opener.open(frame)
    return try {
      JSONObject(String(plaintext, Charsets.UTF_8))
    } catch (error: JSONException) {
      throw IOException("Malformed helper message", error)
    }
  }

  override fun close() {
    try {
      socket.close()
    } catch (_: IOException) {
      // Already closed or reset; nothing left to release.
    }
  }

  private fun readFully(buffer: ByteArray, offset: Int) {
    var read = offset
    while (read < buffer.size) {
      val count = input.read(buffer, read, buffer.size - read)
      if (count < 0) throw EOFException("Helper closed mid-frame")
      read += count
    }
  }
}
