package io.jeph.pipkin.data

import android.util.Log
import io.jeph.pipkin.agent.NoticeLevel
import io.jeph.pipkin.agent.SavedNotice
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The extension notices of each session, by session file path, in one JSON file per session under [dir]. Pi keeps no
 * record of `ctx.ui.notify`, and Pipkin stops a chat's Pi far more often than a desktop terminal does, so this is what
 * brings them back. [load] reads a session's file once and serves the cache after that, and [put] updates the cache
 * while [writer] writes each change in order, so the file always ends up with the latest notices.
 */
internal class NoticeStore(private val dir: File, private val writer: Executor = Executors.newSingleThreadExecutor()) {
  private val cache = HashMap<String, List<SavedNotice>>()

  /** Reads [sessionPath]'s notices into the cache, unless it has them already. Blocking: call off the main thread. */
  fun load(sessionPath: String): List<SavedNotice> {
    synchronized(cache) { cache[sessionPath] }?.let { return it }
    val read = read(fileOf(sessionPath))
    return synchronized(cache) { cache.getOrPut(sessionPath) { read } }
  }

  /** Saves [notices] as [sessionPath]'s; an empty list deletes its file. */
  fun put(sessionPath: String, notices: List<SavedNotice>) {
    val file = fileOf(sessionPath)
    synchronized(cache) {
      if (cache[sessionPath] == notices) return
      cache[sessionPath] = notices
      // Queued under the lock, so the writes land in the order the cache changed.
      writer.execute { write(file, notices) }
    }
  }

  /** Forgets the notices of the deleted sessions [sessionPaths]. */
  fun delete(sessionPaths: Collection<String>) {
    for (path in sessionPaths) put(path, emptyList())
  }

  /**
   * Moves the notices of each session in [renamed], old path to new, to its new path. Blocking until the files have
   * moved, so a [load] afterwards finds them: call off the main thread.
   */
  fun rename(renamed: Map<String, String>) {
    val moved = CountDownLatch(1)
    synchronized(cache) {
      for ((old, new) in renamed) {
        cache.remove(old)?.let { cache[new] = it }
        val from = fileOf(old)
        val to = fileOf(new)
        writer.execute { if (from.exists() && !from.renameTo(to)) Log.w(TAG, "Moving notices to $to failed") }
      }
      writer.execute(moved::countDown)
    }
    moved.await()
  }

  private fun fileOf(sessionPath: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(sessionPath.toByteArray(Charsets.UTF_8))
    return File(dir, digest.joinToString("", postfix = ".json") { "%02x".format(Locale.ROOT, it) })
  }

  private fun write(file: File, notices: List<SavedNotice>) {
    if (notices.isEmpty()) {
      file.delete()
      return
    }
    try {
      dir.mkdirs()
      // Renamed over the old file, so a crash mid-write leaves the previous notices rather than half a file.
      val partial = File(dir, "${file.name}.partial")
      partial.writeText(notices.toJson().toString(), Charsets.UTF_8)
      if (!partial.renameTo(file)) throw IOException("Could not replace $file")
    } catch (error: IOException) {
      Log.w(TAG, "Saving notices to $file failed", error)
    }
  }

  private fun read(file: File): List<SavedNotice> {
    if (!file.exists()) return emptyList()
    return try {
      val array = JSONArray(file.readText(Charsets.UTF_8))
      List(array.length()) { savedNotice(array.getJSONObject(it)) }
    } catch (error: IOException) {
      Log.w(TAG, "Reading notices from $file failed", error)
      emptyList()
    } catch (error: JSONException) {
      Log.w(TAG, "Notices in $file are malformed", error)
      emptyList()
    } catch (error: IllegalArgumentException) {
      Log.w(TAG, "Notices in $file have an unknown level", error)
      emptyList()
    }
  }

  private companion object {
    const val TAG = "Pipkin"
  }
}

private fun List<SavedNotice>.toJson(): JSONArray = JSONArray().also { array ->
  for (notice in this) {
    array.put(
      JSONObject()
        .put("level", notice.level.name)
        .put("message", notice.message)
        .put("after", notice.after ?: JSONObject.NULL)
        .put("afterRun", notice.afterRun),
    )
  }
}

private fun savedNotice(json: JSONObject) = SavedNotice(
  level = NoticeLevel.valueOf(json.getString("level")),
  message = json.getString("message"),
  after = if (json.isNull("after")) null else json.getLong("after"),
  afterRun = json.getBoolean("afterRun"),
)
