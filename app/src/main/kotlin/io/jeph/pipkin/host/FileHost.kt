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

/**
 * The `files` host ran the request and couldn't do it; [message] says why, for the user, in the app's language.
 * [code] names the failure when it was the connection to a remote workspace's host, such as `remote_not_connected`.
 */
internal class FileError(private val reason: HostText, val code: String? = null) : IOException() {
  override val message: String get() = reason.resolve()
}

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
 * for [IDLE_MILLIS]. Requests run concurrently, matched to their replies by id. [isRemote] tells a remote workspace's
 * stand-in, whose requests wait longer. Main-thread confined.
 */
internal class FileHost(
  hostClient: HostClient,
  scope: CoroutineScope,
  private val isRemote: (root: String) -> Boolean = { false },
) : WorkspaceFolders {
  private val host = SharedHost(scope, IDLE_MILLIS, hostClient::openFiles)

  override suspend fun list(root: String, path: String): List<FileEntry> {
    val reply = request(JSONObject().put("type", LIST).put("root", root).put("path", path), listTimeout(root))
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
    val timeout = if (isRemote(root)) REMOTE_READ_TIMEOUT_MILLIS else READ_TIMEOUT_MILLIS
    val reply = request(JSONObject().put("type", READ).put("root", root).put("path", path), timeout)
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
    return request(message, listTimeout(root)).optString("path")
  }

  private fun listTimeout(root: String) = if (isRemote(root)) REMOTE_LIST_TIMEOUT_MILLIS else LIST_TIMEOUT_MILLIS

  private suspend fun request(message: JSONObject, timeoutMillis: Long): JSONObject {
    val reply = host.request(message, timeoutMillis)
    HostText.field(reply, "error")?.let {
      throw FileError(it, (reply.opt("errorCode") as? String)?.takeIf(String::isNotEmpty))
    }
    return reply
  }

  private companion object {
    const val LIST = "pipkin_files_list"
    const val READ = "pipkin_files_read"
    const val MKDIR = "pipkin_files_mkdir"
    const val LIST_TIMEOUT_MILLIS = 15_000L

    /** A 2 MiB text file or 8 MiB image crosses Termux's socket as one JSON line. */
    const val READ_TIMEOUT_MILLIS = 30_000L

    /**
     * Past the host's own limits for a remote workspace, so the user sees why its host can't be reached: `ssh`'s
     * 15-second connect timeout, or 20 seconds of silence plus a 5-second check on a connection that went dead.
     */
    const val REMOTE_LIST_TIMEOUT_MILLIS = 45_000L

    /** As [REMOTE_LIST_TIMEOUT_MILLIS], with time for an 8 MiB image over the network. */
    const val REMOTE_READ_TIMEOUT_MILLIS = 90_000L

    /** Long enough that browsing and reading files in a sitting reuses one host. */
    const val IDLE_MILLIS = 120_000L
  }
}
