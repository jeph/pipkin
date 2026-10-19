package io.jeph.pipkin.host

import android.content.Context
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.termux.TermuxSetup
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Setup of Pipkin's helper in Termux's Play build. Each copied command opens a session on [HostServer]'s loopback
 * port that serves the installer, the helper's files and, encrypted to the command's token, a fresh pairing key.
 * A newer command supersedes the session; it also ends after [SESSION_MILLIS] or once a helper uses its key.
 */
internal object PlaySetup {
  private const val SESSION_MILLIS = 30 * 60_000L
  private val route = Regex("""^/pipkin/([^/]+)/(setup\.sh|helper\.mjs|run\.sh|start\.sh|key)$""")
  private val timers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Volatile
  private var session: SetupSession? = null
  private var hold: AutoCloseable? = null
  private var expiry: Job? = null

  /**
   * Opens a session, superseding the previous one, and returns the one-line command that runs its installer. While
   * the user is in Termux, Android would freeze Pipkin and stall the installer's downloads, so [keepAlive] holds
   * Pipkin in the foreground until a helper pairs or the session expires.
   */
  fun newCommand(context: Context, keepAlive: KeepAlive): String {
    val app = context.applicationContext
    val session = SetupSession.create(
      read = { HelperAssets.read(app, it) },
      packageName = app.packageName,
      expiresAt = System.currentTimeMillis() + SESSION_MILLIS,
      onKeyServed = { HelperPairing.storePending(app, it) },
    )
    open(session)
    synchronized(this) {
      if (hold == null) hold = keepAlive.acquire("Termux setup")
      expiry?.cancel()
      expiry = timers.launch {
        delay(SESSION_MILLIS)
        release()
      }
    }
    return session.command(HostServer.shared.port)
  }

  internal fun open(session: SetupSession) {
    this.session = session
  }

  /** A helper authenticated with [key]: the session that handed it out is done. */
  internal fun complete(key: ByteArray) {
    val session = session?.takeIf { MessageDigest.isEqual(it.key, key) } ?: return
    session.complete()
    release()
  }

  private fun release() = synchronized(this) {
    expiry?.cancel()
    expiry = null
    hold?.close()
    hold = null
  }

  /**
   * Answers `GET [target]`; HostServer writes the response. Pasting a command again after it paired still serves the
   * installer and files, so the rerun repairs everything else while keeping the pairing; only its key is gone.
   */
  internal fun respond(target: String, now: Long = System.currentTimeMillis()): HttpResponse {
    val match = route.matchEntire(target) ?: return HttpResponse(HttpResponse.NOT_FOUND)
    val (sid, name) = match.destructured
    val session = session?.takeIf { now < it.expiresAt && it.matches(sid) } ?: return HttpResponse(HttpResponse.GONE)
    return session.serve(name)
  }
}

/** The APK assets Termux's Play build gets: the helper bundle and the scripts around it. */
internal object HelperAssets {
  const val HELPER = "pipkin-helper.mjs"
  const val INSTALLER = "termux-helper/pipkin-setup.sh"
  const val RUN = "termux-helper/run.sh"
  const val START = "termux-helper/start.sh"

  @Volatile
  private var helperSha256: String? = null

  fun read(context: Context, name: String): ByteArray = context.assets.open(name).use { it.readBytes() }

  /** What a helper running this APK's bundle reports as its version. */
  fun helperSha256(context: Context): String =
    helperSha256 ?: sha256Hex(read(context, HELPER)).also { helperSha256 = it }

  fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
}

/** One copied setup command: its id, its token, the pairing key it hands out and the files it serves. */
internal class SetupSession(
  val sid: String,
  val token: ByteArray,
  val key: ByteArray,
  val expiresAt: Long,
  private val files: Map<String, ByteArray>,
  private val onKeyServed: (ByteArray) -> Unit,
) {
  private val completed = AtomicBoolean()

  /** The command checks the downloaded installer against this before running it. */
  val installerSha256: String = HelperAssets.sha256Hex(files.getValue(INSTALLER))

  fun matches(sid: String): Boolean =
    MessageDigest.isEqual(this.sid.toByteArray(Charsets.US_ASCII), sid.toByteArray(Charsets.US_ASCII))

  fun complete() = completed.set(true)

  /**
   * Serving the key makes it Pipkin's pending key; the installer may fetch it again after a failure, and gets the
   * same key under a fresh nonce, until a helper pairs with it.
   */
  fun serve(name: String): HttpResponse {
    if (name != KEY) return HttpResponse(HttpResponse.OK, files.getValue(name))
    if (completed.get()) return HttpResponse(HttpResponse.GONE)
    val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    onKeyServed(key)
    return HttpResponse(HttpResponse.OK, pairingBody(token, sid, key, nonce))
  }

  /** The line the user pastes into Termux; [TermuxSetup.inBash] runs it in bash whatever Termux's shell is. */
  fun command(port: Int): String {
    val base = "http://127.0.0.1:$port/pipkin/$sid"
    return TermuxSetup.inBash(
      "f=\$(mktemp) && curl -fsS -m 60 \"$base/$INSTALLER\" -o \"\$f\" && " +
        "echo \"$installerSha256  \$f\" | sha256sum -c --status && " +
        "PIPKIN_URL=\"$base\" PIPKIN_TOKEN=${token.toHexString()} bash \"\$f\"; s=\$?; rm -f \"\$f\"; " +
        "[ \$s = 0 ] || echo \"Pipkin setup did not finish. " +
        "Open Pipkin, copy the setup command again and paste it here.\"",
    )
  }

  companion object {
    const val INSTALLER = "setup.sh"
    const val KEY = "key"
    private const val ID_BYTES = 16
    private const val TOKEN_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    fun create(
      read: (String) -> ByteArray,
      packageName: String,
      expiresAt: Long,
      onKeyServed: (ByteArray) -> Unit,
    ): SetupSession {
      val helper = read(HelperAssets.HELPER)
      val run = read(HelperAssets.RUN)
      val start = read(HelperAssets.START)
      val installer = String(read(HelperAssets.INSTALLER), Charsets.UTF_8)
        .replace("@HELPER_SHA256@", HelperAssets.sha256Hex(helper))
        .replace("@RUN_SHA256@", HelperAssets.sha256Hex(run))
        .replace("@START_SHA256@", HelperAssets.sha256Hex(start))
        .replace("@PIPKIN_PACKAGE@", packageName)
        .replace("@PIPKIN_NAME@", TermuxBuild.NAME)
        .replace("@HELPER_PORTS@", HelperProtocol.PORTS.joinToString(","))
      return SetupSession(
        sid = ByteArray(ID_BYTES).also(random::nextBytes).toHexString(),
        token = ByteArray(TOKEN_BYTES).also(random::nextBytes),
        key = ByteArray(HelperProtocol.KEY_BYTES).also(random::nextBytes),
        expiresAt = expiresAt,
        files = mapOf(
          INSTALLER to installer.toByteArray(Charsets.UTF_8),
          "helper.mjs" to helper,
          "run.sh" to run,
          "start.sh" to start,
        ),
        onKeyServed = onKeyServed,
      )
    }

    /**
     * hex(nonce || AES-256-GCM(pk, nonce, key, aad = "pipkin-pair|" + sid) || tag), where
     * pk = HMAC(token, "pipkin-pair-key").
     */
    fun pairingBody(token: ByteArray, sid: String, key: ByteArray, nonce: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pairingKey(token), "AES"), GCMParameterSpec(TAG_BITS, nonce))
      cipher.updateAAD("pipkin-pair|$sid".toByteArray(Charsets.US_ASCII))
      return (nonce + cipher.doFinal(key)).toHexString().toByteArray(Charsets.US_ASCII)
    }

    fun pairingKey(token: ByteArray): ByteArray = HelperProtocol.hmac(token, "pipkin-pair-key")
  }
}

/** The whole of a setup endpoint's answer; every one closes the connection and is never cached. */
internal class HttpResponse(val status: Int, val body: ByteArray = ByteArray(0)) {
  fun write(output: OutputStream) {
    val reason = when (status) {
      OK -> "OK"
      NOT_FOUND -> "Not Found"
      else -> "Gone"
    }
    val head = "HTTP/1.1 $status $reason\r\n" +
      "Content-Type: ${if (status == OK) "application/octet-stream" else "text/plain"}\r\n" +
      "Content-Length: ${body.size}\r\n" +
      "Connection: close\r\n" +
      "Cache-Control: no-store\r\n\r\n"
    output.write(head.toByteArray(Charsets.US_ASCII))
    output.write(body)
    output.flush()
  }

  companion object {
    const val OK = 200
    const val NOT_FOUND = 404
    const val GONE = 410
  }
}
