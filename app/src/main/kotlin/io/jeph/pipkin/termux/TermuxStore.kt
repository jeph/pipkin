package io.jeph.pipkin.termux

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject

internal data class TermuxPairing(
  val installId: String,
  val port: Int,
  val token: String,
  val consent: Boolean,
  val identity: HeldCertificate,
) {
  override fun toString(): String = "TermuxPairing(installId=$installId, port=$port, consent=$consent)"
}

/** Only called under the coordinator's IO mutex. Never stores prompt text or API credentials. */
internal class TermuxStore(context: Context) {
  private val directory = context.noBackupFilesDir
  private val pairingFile = AtomicFile(File(directory, "termux-pairing.json"))
  private val receiptsFile = AtomicFile(File(directory, "termux-receipts.json"))

  fun pairing(): TermuxPairing {
    val stored = read(pairingFile) ?: run {
      val (port, token) = freshEndpoint()
      return TermuxPairing(UUID.randomUUID().toString(), port, token, false, TermuxTls.createIdentity())
        .also(::savePairing)
    }
    val schema = stored.integer("schema")
    if (schema != 1L && schema != 2L) storageFailure()
    val id = stored.string("installId")
    val port = stored.integer("port")
    val token = stored.string(if (schema == 1L) "key" else "token")
    val consent = stored.boolean("consent")
    if (!validUuid(id) || port !in 1024L..65535L || !validDigest(token)) storageFailure()
    val identity = if (schema == 1L) {
      TermuxTls.createIdentity()
    } else {
      TermuxTls.loadIdentity(
        (stored.opt("certificatePem") as? String).orEmpty(),
        (stored.opt("privateKeyPem") as? String).orEmpty(),
      )
    }
    val pairing = TermuxPairing(id, port.toInt(), token, consent, identity)
    // Migration keeps the root, endpoint, bearer secret, consent and separate receipt journal.
    if (schema == 1L) savePairing(pairing)
    return pairing
  }

  fun consent(pairing: TermuxPairing): TermuxPairing = pairing.copy(consent = true).also(::savePairing)

  /** Explicit repair can replace an expired/broken TLS identity without discarding the data root. */
  fun repair(): TermuxPairing {
    val stored = read(pairingFile) ?: return pairing()
    if (stored.integer("schema") !in 1L..2L) storageFailure()
    val id = stored.string("installId")
    val oldPort = stored.integer("port")
    val consent = stored.boolean("consent")
    if (!validUuid(id) || oldPort !in 1024L..65535L) storageFailure()
    var endpoint = freshEndpoint()
    while (endpoint.first.toLong() == oldPort) endpoint = freshEndpoint()
    return TermuxPairing(id, endpoint.first, endpoint.second, consent, TermuxTls.createIdentity())
      .also(::savePairing)
  }

  fun pending(id: String, method: String, instance: String, session: String?, generation: Long) {
    val receipts = receipts()
    val unresolved = receipts.count { it.string("outcome") == "uncertain" }
    if (unresolved >= 96) {
      throw RuntimeFailure(
        RuntimeFailureKind.Storage,
        "Too many unconfirmed requests. Reconnect to reconcile before sending more work.",
      )
    }
    receipts.add(
      JSONObject().put("id", id).put("method", method).put("instance", instance)
        .put("session", session ?: JSONObject.NULL).put("generation", generation)
        .put("outcome", "uncertain"),
    )
    saveReceipts(receipts)
  }

  fun outcome(id: String, outcome: String) {
    val receipts = receipts()
    receipts.firstOrNull { it.string("id") == id }?.put("outcome", outcome)
    saveReceipts(receipts)
  }

  fun reconcile(snapshot: JSONObject): Int {
    val authoritative = snapshot.optJSONArray("receipts") ?: protocolFailure()
    if (authoritative.length() > 1024) protocolFailure()
    val receipts = receipts()
    var changed = false
    for (index in 0 until authoritative.length()) {
      val remote = authoritative.optJSONObject(index) ?: protocolFailure()
      val id = remote.opt("id")
      val outcome = remote.string("outcome")
      if (outcome !in setOf("accepted", "rejected", "uncertain")) protocolFailure()
      // Other JSON-RPC clients may use numeric/null IDs; only our UUID strings can match.
      val local = receipts.firstOrNull { it.string("id") == id } ?: continue
      if (local.string("method") != remote.string("method")) protocolFailure()
      if (local.string("outcome") == "uncertain" && outcome != "uncertain") {
        local.put("outcome", outcome)
        changed = true
      }
    }
    if (changed) saveReceipts(receipts)
    // Missing/expired receipts remain uncertain forever; lack of evidence is not a rejection.
    return receipts.count { it.string("outcome") == "uncertain" }
  }

  private fun freshEndpoint(): Pair<Int, String> =
    32768 + SecureRandom().nextInt(28232) to ByteArray(32).also { SecureRandom().nextBytes(it) }.hex()

  private fun savePairing(pairing: TermuxPairing) = save(
    pairingFile,
    JSONObject().put("schema", 2).put("installId", pairing.installId).put("port", pairing.port)
      .put("token", pairing.token).put("consent", pairing.consent)
      .put("certificatePem", pairing.identity.certificatePem())
      .put("privateKeyPem", pairing.identity.privateKeyPkcs8Pem()),
  )

  private fun receipts(): MutableList<JSONObject> {
    val stored = read(receiptsFile) ?: return mutableListOf()
    if (stored.integer("schema") != 1L) storageFailure()
    val values = stored.optJSONArray("receipts") ?: storageFailure()
    if (values.length() > 128) storageFailure()
    return MutableList(values.length()) { index ->
      val record = values.optJSONObject(index) ?: storageFailure()
      if (!validUuid(record.string("id")) || record.string("outcome") !in setOf("accepted", "rejected", "uncertain")) {
        storageFailure()
      }
      record
    }
  }

  private fun saveReceipts(receipts: List<JSONObject>) {
    val unresolved = receipts.filter { it.string("outcome") == "uncertain" }
    val completed = receipts.filter { it.string("outcome") != "uncertain" }.takeLast(32)
    save(receiptsFile, JSONObject().put("schema", 1).put("receipts", JSONArray(unresolved + completed)))
  }

  private fun read(file: AtomicFile): JSONObject? = try {
    file.openRead().use { input ->
      val bytes = ByteArray(128 * 1024 + 1)
      var count = 0
      while (count < bytes.size) {
        val read = input.read(bytes, count, bytes.size - count)
        if (read < 0) break
        count += read
      }
      if (count > 128 * 1024) storageFailure()
      strictObject(bytes.copyOf(count))
    }
  } catch (_: FileNotFoundException) {
    if (file.baseFile.exists()) storageFailure()
    null
  } catch (error: IOException) {
    throw RuntimeFailure(
      RuntimeFailureKind.Storage,
      "Private Termux pairing or request history could not be read. It was not reset.",
      error,
    )
  }

  @Suppress("TooGenericExceptionCaught") // AtomicFile.failWrite must run for any failure.
  private fun save(file: AtomicFile, value: JSONObject) {
    try {
      val output = file.startWrite()
      try {
        output.write(value.toString().toByteArray(Charsets.UTF_8))
        output.fd.sync()
        file.finishWrite(output)
      } catch (error: Exception) {
        file.failWrite(output)
        throw error
      }
      val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
      try {
        Os.fsync(descriptor)
      } finally {
        Os.close(descriptor)
      }
    } catch (error: Exception) {
      throw RuntimeFailure(
        RuntimeFailureKind.Storage,
        "Could not durably save private Termux state. No request was sent.",
        error,
      )
    }
  }

  private fun storageFailure(): Nothing = throw RuntimeFailure(
    RuntimeFailureKind.Storage,
    "Private Termux state is invalid. It was preserved, not silently replaced.",
  )
}
