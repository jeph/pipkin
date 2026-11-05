package io.jeph.pipkin.data

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import io.jeph.pipkin.host.DEFAULT_SSH_PORT
import io.jeph.pipkin.host.RemoteAuth
import io.jeph.pipkin.host.RemoteHost
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

/**
 * The passwords the user asked Pipkin to remember for password hosts, kept in Pipkin's own storage, never in Termux,
 * encrypted by [cipher]. Each is bound to its host's id, address and port, and to the exact prompt it answered, as
 * authenticated data: it doesn't decrypt for another host or server, and Pipkin fills it in only for that same
 * question. One that can't be decrypted counts as not saved, and is deleted.
 */
internal class RemotePasswords(private val file: File, private val cipher: PasswordCipher) {
  /** A saved password and the prompt, in `ssh`'s words, it answers. */
  data class Saved(val prompt: String, val password: String)

  private class Entry(val destination: String, val prompt: String, val sealed: ByteArray, val rejected: Boolean)

  private val lock = Any()
  private val entries = mutableMapOf<String, Entry>()
  private val rejectedState = MutableStateFlow<Map<String, Boolean>>(emptyMap())

  /**
   * The hosts with a saved password, by id, each with whether the server rejected it the last time Pipkin sent it,
   * after which Pipkin doesn't send it again.
   */
  val saved: StateFlow<Map<String, Boolean>> = rejectedState.asStateFlow()

  init {
    synchronized(lock) {
      entries.putAll(read())
      publish()
    }
  }

  /** [host]'s password, to send; null when none is saved for it as it is now, or the server rejected it. */
  fun password(host: RemoteHost): Saved? = synchronized(lock) {
    val entry = entries[host.id]?.takeIf { !it.rejected && it.destination == destination(host) } ?: return null
    try {
      val plaintext = cipher.open(entry.sealed, binding(host.id, entry.destination, entry.prompt))
      Saved(entry.prompt, plaintext.toString(Charsets.UTF_8))
    } catch (error: GeneralSecurityException) {
      // For example when Android reset the Keystore: the password is gone, so the next connect asks for it.
      Log.w(TAG, "Deleting a saved password that no longer decrypts", error)
      entries.remove(host.id)
      write()
      null
    }
  }

  /**
   * Saves [password] for [host], replacing any other, as the answer to [prompt]. When the Keystore can't seal it, the
   * host has no saved password afterwards, so the next connect asks rather than sending an older one.
   */
  fun save(host: RemoteHost, prompt: String, password: String) = synchronized(lock) {
    val destination = destination(host)
    try {
      val sealed = cipher.seal(password.toByteArray(Charsets.UTF_8), binding(host.id, destination, prompt))
      entries[host.id] = Entry(destination, prompt, sealed, rejected = false)
    } catch (error: GeneralSecurityException) {
      Log.w(TAG, "Couldn't encrypt the password to save", error)
      entries.remove(host.id)
    }
    write()
  }

  /** Records that host [id]'s server refused its saved password, so Pipkin stops sending it. */
  fun reject(id: String) {
    synchronized(lock) {
      val entry = entries[id] ?: return
      entries[id] = Entry(entry.destination, entry.prompt, entry.sealed, rejected = true)
      write()
    }
  }

  fun forget(id: String) = synchronized(lock) {
    if (entries.remove(id) != null) write()
  }

  /**
   * Deletes the passwords no host in [hosts] signs in with any more: those of removed hosts, of hosts that switched to
   * another way of signing in, and of hosts whose address or port changed, which may be another server.
   */
  fun prune(hosts: List<RemoteHost>) = synchronized(lock) {
    val kept = hosts.filter { it.auth == RemoteAuth.Password }.associate { it.id to destination(it) }
    if (entries.entries.retainAll { (id, entry) -> kept[id] == entry.destination }) write()
  }

  /** Whether saving [edited] in place of [current] deletes the password saved for it ([prune]). */
  fun forgetsOnSave(current: RemoteHost, edited: RemoteHost): Boolean = synchronized(lock) {
    entries.containsKey(current.id) &&
      (edited.auth != RemoteAuth.Password || destination(edited) != destination(current))
  }

  private fun publish() {
    rejectedState.value = entries.mapValues { it.value.rejected }
  }

  private fun read(): Map<String, Entry> {
    if (!file.exists()) return emptyMap()
    return try {
      val json = JSONObject(file.readText())
      json.keys().asSequence().associateWith { id ->
        val entry = json.getJSONObject(id)
        Entry(
          destination = entry.getString("destination"),
          prompt = entry.getString("prompt"),
          sealed = Base64.getDecoder().decode(entry.getString("sealed")),
          rejected = entry.optBoolean("rejected"),
        )
      }
    } catch (error: JSONException) {
      // Only Pipkin writes this file, so it's damaged; the passwords are asked for again.
      Log.w(TAG, "Ignoring an unreadable saved passwords file", error)
      emptyMap()
    } catch (error: IllegalArgumentException) {
      Log.w(TAG, "Ignoring an unreadable saved passwords file", error)
      emptyMap()
    }
  }

  private fun write() {
    publish()
    val json = JSONObject()
    for ((id, entry) in entries) {
      json.put(
        id,
        JSONObject()
          .put("destination", entry.destination)
          .put("prompt", entry.prompt)
          .put("sealed", Base64.getEncoder().encodeToString(entry.sealed))
          .put("rejected", entry.rejected),
      )
    }
    val temp = File(file.parentFile, "${file.name}.tmp")
    FileOutputStream(temp).use { output ->
      output.write(json.toString().toByteArray(Charsets.UTF_8))
      output.fd.sync()
    }
    if (!temp.renameTo(file)) {
      temp.delete()
      throw IOException("Can't replace ${file.name}")
    }
  }

  companion object {
    private const val TAG = "PipkinRemotePasswords"
    private const val FILE = "remote-passwords.json"

    /** The store in `noBackupFilesDir`, which Android's backups and device transfers never copy. */
    fun of(context: Context) = RemotePasswords(File(context.noBackupFilesDir, FILE), KeystoreCipher(context))

    /** The server a password is for: [host]'s address and port, with SSH's own port when it has none. */
    private fun destination(host: RemoteHost) = "${host.address}:${host.port ?: DEFAULT_SSH_PORT}"

    private fun binding(id: String, destination: String, prompt: String): ByteArray =
      listOf(id, destination, prompt).joinToString("\u0000").toByteArray(Charsets.UTF_8)
  }
}

/**
 * Authenticated encryption for [RemotePasswords]; [seal] and [open] throw [GeneralSecurityException] for any failure,
 * and [open] for anything it didn't [seal].
 */
internal interface PasswordCipher {
  fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray

  fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray
}

/**
 * AES-256-GCM under a key that lives in the Android Keystore, in the phone's StrongBox security chip when it has one,
 * and can't be exported. It works while the phone is locked, so Pipkin can reconnect during a turn with the screen off.
 */
private class KeystoreCipher(context: Context) : PasswordCipher {
  private val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

  override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray = keystore {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    cipher.updateAAD(associatedData)
    cipher.iv + cipher.doFinal(plaintext)
  }

  override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray = keystore {
    if (sealed.size <= IV_BYTES) throw GeneralSecurityException("Sealed password too short")
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
    cipher.updateAAD(associatedData)
    cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
  }

  /**
   * Runs a Keystore [operation]. Android's Keystore also fails with [ProviderException], unchecked, for example when
   * its key operation fails or the secure hardware is busy; it's a [GeneralSecurityException] like the others here.
   */
  private inline fun <T> keystore(operation: () -> T): T = try {
    operation()
  } catch (error: ProviderException) {
    throw GeneralSecurityException(error)
  }

  @Synchronized
  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    if (strongBox) {
      try {
        return generate(strongBox = true)
      } catch (_: ProviderException) {
        // Advertised but not usable for this key, as StrongBoxUnavailableException or, on some phones, plain
        // ProviderException; the Keystore's own hardware still keeps it.
      }
    }
    return generate(strongBox = false)
  }

  private fun generate(strongBox: Boolean): SecretKey {
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(KEY_BITS)
      .setIsStrongBoxBacked(strongBox)
      .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
  }

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "remote-passwords"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val KEY_BITS = 256
    const val IV_BYTES = 12
    const val TAG_BITS = 128
  }
}
