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
internal data class FolderListing(val path: String, val parent: String?, val dirs: List<String>, val error: String?)

/**
 * One `host.mjs browse` process kept open while the folder browser is shown, so only opening it pays
 * for starting Termux. Listings are one at a time; [close] ends the host.
 */
internal class FolderBrowser(private val channel: HostChannel, val places: List<FolderPlace>) : AutoCloseable {
  private val lock = Mutex()
  private var nextId = 0L

  suspend fun list(path: String): FolderListing = lock.withLock {
    val id = ++nextId
    try {
      channel.send(JSONObject().put("type", LIST).put("id", id).put("path", path))
    } catch (error: IOException) {
      throw HostFailure.Disconnected(error)
    }
    val line = withTimeoutOrNull(LIST_TIMEOUT_MILLIS) {
      channel.lines.firstOrNull { it.optString("type") == LISTING && it.optLong("id") == id }
        ?: throw HostFailure.Disconnected()
    } ?: throw HostFailure.Timeout()
    FolderListing(
      path = line.optString("path"),
      parent = line.opt("parent") as? String,
      dirs = line.optJSONArray("dirs").strings(),
      error = line.opt("error") as? String,
    )
  }

  override fun close() = channel.close()

  companion object {
    private const val LIST = "pipkin_list"
    private const val LISTING = "pipkin_listing"
    private const val PLACES = "pipkin_places"
    private const val LIST_TIMEOUT_MILLIS = 15_000L

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
