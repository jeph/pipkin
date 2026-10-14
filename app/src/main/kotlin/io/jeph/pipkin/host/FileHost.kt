package io.jeph.pipkin.host

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
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
internal class FileHost(hostClient: HostClient, scope: CoroutineScope) : WorkspaceFolders {
  private val host = SharedHost(scope, IDLE_MILLIS, hostClient::openFiles)

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
    val reply = host.request(message, timeoutMillis)
    reply.optString("error").takeIf { it.isNotEmpty() }?.let { throw FileError(it) }
    return reply
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
