package io.jeph.pipkin.host

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * The pairing keys Pipkin shares with its helper in Termux's Play build: [Keys.current], which a helper has
 * authenticated with, and [Keys.pending], handed out by the latest setup command and not yet seen in use. Kept in
 * `noBackupFilesDir`, so a restored backup never carries them to another device.
 */
internal object HelperPairing {
  private const val FILE = "helper-pairing.json"

  /** One lock for every [Store] of the file: the object hands out a fresh one per call. */
  private val lock = Any()

  class Keys(val current: ByteArray?, val pending: ByteArray?) {
    /** Current first: it's the one a running helper most likely holds. */
    val all: List<ByteArray> get() = listOfNotNull(current, pending)
  }

  /** A helper holds, or is being given, a key Pipkin knows. */
  fun isPaired(context: Context): Boolean = keys(context).all.isNotEmpty()

  fun clear(context: Context) = Store(file(context)).clear()

  internal fun keys(context: Context): Keys = Store(file(context)).keys()

  /** Served by the setup command's `/key`; replaces an older pending key. */
  internal fun storePending(context: Context, key: ByteArray) = Store(file(context)).storePending(key)

  /** A helper authenticated with [key]; when that's the pending key, it becomes the current one. */
  internal fun confirm(context: Context, key: ByteArray) = Store(file(context)).confirm(key)

  private fun file(context: Context) = File(context.noBackupFilesDir, FILE)

  /** The file format and its atomic updates, apart from [Context] so tests can run them. */
  internal class Store(private val file: File) {
    fun keys(): Keys = synchronized(lock) { read() }

    fun clear() = synchronized(lock) {
      if (file.exists() && !file.delete()) throw IOException("Can't delete $FILE")
    }

    fun storePending(key: ByteArray) = synchronized(lock) {
      write(Keys(read().current, key))
    }

    /** Returns whether [key] was the pending key, now promoted. */
    fun confirm(key: ByteArray): Boolean = synchronized(lock) {
      val keys = read()
      if (keys.pending?.contentEquals(key) != true) return false
      write(Keys(key, null))
      true
    }

    private fun read(): Keys {
      if (!file.exists()) return Keys(null, null)
      return try {
        val json = JSONObject(file.readText())
        Keys(json.key("current"), json.key("pending"))
      } catch (_: JSONException) {
        Keys(null, null)
      } catch (_: IllegalArgumentException) {
        // Not hex: only Pipkin writes this file, so it's damaged; pairing starts over.
        Keys(null, null)
      }
    }

    private fun JSONObject.key(name: String): ByteArray? {
      if (isNull(name)) return null
      val hex = optString(name)
      return if (hex.length == HelperProtocol.KEY_BYTES * 2) hex.hexToByteArray() else null
    }

    private fun write(keys: Keys) {
      val json = JSONObject()
        .put("current", keys.current?.toHexString() ?: JSONObject.NULL)
        .put("pending", keys.pending?.toHexString() ?: JSONObject.NULL)
      val temp = File(file.parentFile, "${file.name}.tmp")
      FileOutputStream(temp).use { output ->
        output.write(json.toString().toByteArray(Charsets.UTF_8))
        output.fd.sync()
      }
      if (!temp.renameTo(file)) {
        temp.delete()
        throw IOException("Can't replace $FILE")
      }
    }
  }
}
