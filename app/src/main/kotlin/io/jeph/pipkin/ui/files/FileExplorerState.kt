package io.jeph.pipkin.ui.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.jeph.pipkin.host.FileEntry
import io.jeph.pipkin.host.FileHost
import io.jeph.pipkin.host.HostRejected
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The workspace's top folder, as the `files` host spells it. */
internal const val ROOT_FOLDER = "."

/** One row of the explorer's tree: an entry at its [depth], or the name field of a folder being created. */
internal sealed interface ExplorerRow {
  data class Entry(val entry: FileEntry, val depth: Int) : ExplorerRow

  data object NewFolder : ExplorerRow
}

/**
 * The visible tree, depth first as Paseo's `flattenExplorerTree` builds it: each listed folder's entries in the host's
 * order (folders first), an expanded folder's entries right under it. Dot entries show only with [showHidden].
 */
internal fun explorerRows(
  directories: Map<String, List<FileEntry>>,
  expanded: Set<String>,
  showHidden: Boolean,
): List<ExplorerRow.Entry> {
  val rows = mutableListOf<ExplorerRow.Entry>()
  fun addFolder(path: String, depth: Int) {
    for (entry in directories[path].orEmpty()) {
      if (!showHidden && entry.name.startsWith('.')) continue
      rows += ExplorerRow.Entry(entry, depth)
      if (entry.isDirectory && entry.path in expanded) addFolder(entry.path, depth + 1)
    }
  }
  addFolder(ROOT_FOLDER, 0)
  return rows
}

/**
 * One workspace's file tree: the folders listed so far, which are expanded, which are loading, and why the top folder
 * couldn't be listed. Folder listings come from [files]; failures other than the top folder's are returned for the
 * caller to show. Main-thread confined.
 */
@Stable
internal class FileExplorerState(val root: String, private val files: FileHost) {
  var directories by mutableStateOf(emptyMap<String, List<FileEntry>>())
    private set
  var expanded by mutableStateOf(emptySet<String>())
    private set
  var loading by mutableStateOf(emptySet<String>())
    private set

  /** Why the top folder couldn't be listed; the explorer shows it in place of the tree. */
  var failure by mutableStateOf<String?>(null)
    private set
  var refreshing by mutableStateOf(false)
    private set

  /** The new folder's name field shows at the top of the tree. */
  var creatingFolder by mutableStateOf(false)

  /**
   * Lists the top folder and every expanded one again, together, since Pi may have changed any of them. An expanded
   * folder that can't be listed any more (deleted or renamed) collapses; when the top folder itself can't be, the
   * whole tree goes, so its failure shows instead of files that may no longer be there.
   */
  suspend fun refresh() {
    if (refreshing) return
    refreshing = true
    try {
      val paths = listOf(ROOT_FOLDER) + expanded
      val failures = coroutineScope { paths.map { async { it to fetch(it) } }.awaitAll() }.toMap()
      val rootFailure = failures[ROOT_FOLDER]
      if (rootFailure != null) {
        failure = rootFailure
        expanded = emptySet()
        directories = emptyMap()
        return
      }
      for ((path, error) in failures) if (error != null) collapse(path)
    } finally {
      refreshing = false
    }
  }

  /** Expands [folder], listing it again behind what was listed before; returns why it couldn't be listed. */
  suspend fun toggle(folder: FileEntry): String? {
    if (folder.path in expanded) {
      expanded -= folder.path
      return null
    }
    expanded += folder.path
    return fetch(folder.path)?.also { collapse(folder.path) }
  }

  /** Creates folder [name] at the top of the workspace; returns why it couldn't. */
  suspend fun createFolder(name: String): String? {
    creatingFolder = false
    return attempt { files.createFolder(root, ROOT_FOLDER, name) } ?: fetch(ROOT_FOLDER)
  }

  private fun collapse(path: String) {
    expanded -= path
    directories -= path
  }

  private suspend fun fetch(path: String): String? {
    loading += path
    try {
      return attempt {
        val entries = files.list(root, path)
        directories += path to entries
        if (path == ROOT_FOLDER) failure = null
      }
    } finally {
      loading -= path
    }
  }

  private suspend fun attempt(block: suspend () -> Unit): String? = try {
    block()
    null
  } catch (error: HostRejected) {
    error.error
  } catch (error: IOException) {
    error.message.orEmpty()
  }
}
