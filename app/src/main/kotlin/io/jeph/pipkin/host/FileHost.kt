package io.jeph.pipkin.host

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** One entry of a workspace folder. [path] is relative to the workspace, `/`-separated; [modified] in epoch millis. */
internal data class FileEntry(
  val name: String,
  val path: String,
  val isDirectory: Boolean,
  val size: Long,
  val modified: Long,
)

/** A workspace file as the `files` host read it. */
internal sealed interface FileContent {
  val size: Long

  class Text(val text: String, override val size: Long) : FileContent

  class Image(val bytes: ByteArray, override val size: Long) : FileContent

  class Binary(override val size: Long) : FileContent

  class TooLarge(override val size: Long) : FileContent
}

/** The `files` host ran the request and couldn't do it; [message] says why, for the user. */
internal class FileError(message: String) : IOException(message)

/** A workspace's folders as the file explorer lists and adds to them. */
internal interface WorkspaceFolders {
  /** The entries of folder [path] in workspace [root], folders first; hidden ones included. */
  suspend fun list(root: String, path: String): List<FileEntry>

  /** Creates folder [name] in folder [parent] of workspace [root]; returns its workspace-relative path. */
  suspend fun createFolder(root: String, parent: String, name: String): String
}

/**
 * Workspace files through one long-lived `files` host shared by every workspace, so expanding a folder or opening a
 * file doesn't pay for starting Termux each time. The host starts with the first request and ends once none has come
 * for [IDLE_MILLIS]. Requests run concurrently, matched to their replies by id. Main-thread confined.
 */
internal class FileHost(private val hostClient: HostClient, private val scope: CoroutineScope) : WorkspaceFolders {
  private val openLock = Mutex()
  private var connection: Connection? = null
  private var idleClose: Job? = null
  private var inFlight = 0
  private var nextId = 0L

  override suspend fun list(root: String, path: String): List<FileEntry> {
    val reply = request(JSONObject().put("type", LIST).put("root", root).put("path", path), LIST_TIMEOUT_MILLIS)
    return reply.optJSONArray("entries").objects().map {
      FileEntry(
        name = it.optString("name"),
        path = it.optString("path"),
        isDirectory = it.optString("kind") == "directory",
        size = it.optLong("size"),
        modified = it.optLong("modified"),
      )
    }
  }

  suspend fun read(root: String, path: String): FileContent {
    val reply = request(JSONObject().put("type", READ).put("root", root).put("path", path), READ_TIMEOUT_MILLIS)
    val size = reply.optLong("size")
    return when (reply.optString("kind")) {
      "text" -> FileContent.Text(reply.optString("content"), size)
      "image" -> FileContent.Image(Base64.getDecoder().decode(reply.optString("content")), size)
      "too_large" -> FileContent.TooLarge(size)
      else -> FileContent.Binary(size)
    }
  }

  override suspend fun createFolder(root: String, parent: String, name: String): String {
    val message = JSONObject().put("type", MKDIR).put("root", root).put("path", parent).put("name", name)
    return request(message, LIST_TIMEOUT_MILLIS).optString("path")
  }

  private suspend fun request(message: JSONObject, timeoutMillis: Long): JSONObject {
    idleClose?.cancel()
    inFlight++
    try {
      val reply = connection().request(message.put("id", ++nextId), timeoutMillis)
      reply.optString("error").takeIf { it.isNotEmpty() }?.let { throw FileError(it) }
      return reply
    } finally {
      inFlight--
      if (inFlight == 0) {
        idleClose = scope.launch {
          delay(IDLE_MILLIS)
          connection?.close()
          connection = null
        }
      }
    }
  }

  /** The open host, or a new one when there is none or it ended; concurrent first requests share one start. */
  private suspend fun connection(): Connection = openLock.withLock {
    connection?.takeIf { it.alive } ?: Connection(hostClient.openFiles(), scope).also { connection = it }
  }

  private class Connection(private val channel: HostChannel, scope: CoroutineScope) {
    private val pending = HashMap<Long, CompletableDeferred<JSONObject>>()
    private val reader = scope.launch {
      channel.lines.collect { line -> pending.remove(line.optLong("id", -1))?.complete(line) }
      val failure = HostFailure.Disconnected()
      pending.values.forEach { it.completeExceptionally(failure) }
      pending.clear()
    }

    val alive: Boolean get() = reader.isActive

    suspend fun request(message: JSONObject, timeoutMillis: Long): JSONObject {
      val id = message.getLong("id")
      val reply = CompletableDeferred<JSONObject>()
      pending[id] = reply
      try {
        if (!alive) throw HostFailure.Disconnected()
        try {
          channel.send(message)
        } catch (error: IOException) {
          throw HostFailure.Disconnected(error)
        }
        return withTimeoutOrNull(timeoutMillis) { reply.await() } ?: throw HostFailure.Timeout()
      } finally {
        pending.remove(id)
      }
    }

    fun close() = channel.close()
  }

  private companion object {
    const val LIST = "pipkin_files_list"
    const val READ = "pipkin_files_read"
    const val MKDIR = "pipkin_files_mkdir"
    const val LIST_TIMEOUT_MILLIS = 15_000L

    /** A 2 MiB text file or 8 MiB image crosses Termux's socket as one JSON line. */
    const val READ_TIMEOUT_MILLIS = 30_000L

    /** Long enough that browsing and reading files in a sitting reuses one host. */
    const val IDLE_MILLIS = 120_000L
  }
}
