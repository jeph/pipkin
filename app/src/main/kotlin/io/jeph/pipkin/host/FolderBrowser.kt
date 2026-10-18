package io.jeph.pipkin.host

import java.io.IOException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** A starting point offered by the browser. [kind] is `home`, `shared` or `volume` (with its [name]). */
internal data class FolderPlace(val kind: String, val path: String, val name: String?)

/** Subfolders of [path], as Termux sees them. [error] explains an unreadable path; [parent] is null at `/`. */
internal data class FolderListing(val path: String, val parent: String?, val dirs: List<String>, val error: HostText?)

/**
 * One `host.mjs browse` process kept open while the folder browser is shown, so only opening it pays
 * for starting Termux. Requests are one at a time; [close] ends the host.
 */
internal class FolderBrowser(private val channel: HostChannel, val places: List<FolderPlace>) : AutoCloseable {
  private val lock = Mutex()
  private var nextId = 0L

  suspend fun list(path: String): FolderListing {
    val line = request(JSONObject().put("type", LIST).put("path", path), LISTING, LIST_TIMEOUT_MILLIS)
    return FolderListing(
      path = line.optString("path"),
      parent = line.opt("parent") as? String,
      dirs = line.optJSONArray("dirs").strings(),
      error = HostText.field(line, "error"),
    )
  }

  /**
   * Folders matching [query] in every place, best first; none for a blank query. Cancelling the caller
   * frees the browser for the next request, which skips the dropped search's answer; the host stops
   * that search once a newer one arrives.
   */
  suspend fun search(query: String): List<String> =
    request(JSONObject().put("type", SEARCH).put("query", query), SEARCH_RESULTS, SEARCH_TIMEOUT_MILLIS)
      .optJSONArray("paths")
      .strings()

  private suspend fun request(message: JSONObject, replyType: String, timeoutMillis: Long): JSONObject = lock.withLock {
    val id = ++nextId
    try {
      channel.send(message.put("id", id))
    } catch (error: IOException) {
      throw HostFailure.Disconnected(error)
    }
    withTimeoutOrNull(timeoutMillis) {
      channel.lines.firstOrNull { it.optString("type") == replyType && it.optLong("id") == id }
        ?: throw HostFailure.Disconnected()
    } ?: throw HostFailure.Timeout()
  }

  override fun close() = channel.close()

  companion object {
    private const val LIST = "pipkin_list"
    private const val LISTING = "pipkin_listing"
    private const val PLACES = "pipkin_places"
    private const val SEARCH = "pipkin_search"
    private const val SEARCH_RESULTS = "pipkin_search_results"
    private const val LIST_TIMEOUT_MILLIS = 15_000L

    /** A search walks up to 20,000 folders per place, which takes a while on shared storage. */
    private const val SEARCH_TIMEOUT_MILLIS = 60_000L

    /** Waits for the host's `pipkin_places`; a refusal arrives as `pipkin_result` instead. */
    suspend fun start(channel: HostChannel): FolderBrowser {
      val line = withTimeoutOrNull(LIST_TIMEOUT_MILLIS) {
        channel.lines.firstOrNull { it.optString("type") == PLACES || it.optString("type") == HostProtocol.RESULT }
          ?: throw HostFailure.Disconnected()
      } ?: throw HostFailure.Timeout()
      if (line.optString("type") == HostProtocol.RESULT) throw HostRejected.from(line) ?: HostFailure.Disconnected()
      val places = line.optJSONArray("places").objects().map {
        FolderPlace(it.optString("kind"), it.optString("path"), it.opt("name") as? String)
      }
      return FolderBrowser(channel, places)
    }
  }
}
