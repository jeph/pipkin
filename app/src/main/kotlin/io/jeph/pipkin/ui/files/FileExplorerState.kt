package io.jeph.pipkin.ui.files

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import io.jeph.pipkin.host.FileEntry
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.WorkspaceFolders
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The workspace's top folder, as the `files` host spells it. */
internal const val ROOT_FOLDER = "."

/** The lazy-list key of the new folder's name field, which can't clash with a path. */
internal const val NEW_FOLDER_KEY = "/new-folder"

/** One row of the explorer's tree: an entry at its [depth], or the name field of a folder being created. */
internal sealed interface ExplorerRow {
  data class Entry(val entry: FileEntry, val depth: Int) : ExplorerRow

  data object NewFolder : ExplorerRow
}

/**
 * The visible tree, depth first as Paseo's `flattenExplorerTree` builds it: each listed folder's entries in [sort]'s
 * order, an expanded folder's entries right under it. Dot entries show only with [showHidden].
 */
internal fun explorerRows(
  directories: Map<String, List<FileEntry>>,
  expanded: Set<String>,
  showHidden: Boolean,
  sort: ExplorerSort,
): List<ExplorerRow.Entry> {
  val rows = mutableListOf<ExplorerRow.Entry>()
  fun addFolder(path: String, depth: Int) {
    for (entry in sortEntries(directories[path].orEmpty(), sort)) {
      if (!showHidden && entry.name.startsWith('.')) continue
      rows += ExplorerRow.Entry(entry, depth)
      if (entry.isDirectory && entry.path in expanded) addFolder(entry.path, depth + 1)
    }
  }
  addFolder(ROOT_FOLDER, 0)
  return rows
}

/** The row at the top of the explorer's list, by path, and how far it is scrolled past. */
internal data class ScrollAnchor(val path: String, val offset: Int)

/** A place in the explorer's list: a row's index and how far it is scrolled past. */
internal data class ListPosition(val index: Int, val offset: Int)

/**
 * Where [anchor] is in [rows], which may differ from the rows it was taken from since the files may have changed: its
 * own row, or when that's gone (deleted, renamed, or a dot entry now hidden) the row of its nearest folder still shown,
 * from that row's top; the top of the tree when none of them is.
 */
internal fun listPosition(rows: List<ExplorerRow.Entry>, anchor: ScrollAnchor): ListPosition {
  val indexes = rows.withIndex().associate { (index, row) -> row.entry.path to index }
  indexes[anchor.path]?.let { return ListPosition(it, anchor.offset) }
  var folder = anchor.path.substringBeforeLast('/', missingDelimiterValue = "")
  while (folder.isNotEmpty()) {
    indexes[folder]?.let { return ListPosition(it, 0) }
    folder = folder.substringBeforeLast('/', missingDelimiterValue = "")
  }
  return ListPosition(0, 0)
}

/**
 * The tree of every workspace the explorer has shown, by workspace folder. Saved with the activity's state, each keeps
 * its expanded folders and scroll position when the activity is recreated (a foldable folding or unfolding) or the
 * main screen leaves for another page, but not its listings, which are listed again as the explorer opens.
 */
internal class ExplorerTrees(private val files: WorkspaceFolders) {
  private val trees = HashMap<String, FileExplorerState>()

  operator fun get(root: String): FileExplorerState = trees.getOrPut(root) { FileExplorerState(root, files) }

  companion object {
    /** Each tree as `[root, expanded folders, top row's path or null, its offset or null]`. */
    fun saver(files: WorkspaceFolders) = Saver<ExplorerTrees, ArrayList<ArrayList<Any?>>>(
      save = { explorer ->
        explorer.trees.values.mapTo(ArrayList()) { tree ->
          val anchor = tree.scrollAnchor()
          arrayListOf(tree.root, ArrayList(tree.expanded), anchor?.path, anchor?.offset)
        }
      },
      restore = { saved ->
        ExplorerTrees(files).apply {
          for (tree in saved) {
            val root = tree[0] as String
            trees[root] = FileExplorerState(
              root = root,
              files = files,
              initiallyExpanded = (tree[1] as List<*>).mapTo(HashSet()) { it as String },
              savedAnchor = (tree[2] as String?)?.let { ScrollAnchor(it, tree[3] as Int) },
            )
          }
        }
      },
    )
  }
}

/**
 * One workspace's file tree: the folders listed so far, which are expanded, which are loading, why the top folder
 * couldn't be listed, and the list's scroll position. Folder listings come from [files]; failures other than the top
 * folder's are returned for the caller to show. A tree restored from saved state starts with [initiallyExpanded] and
 * nothing listed, and waits to scroll back to [savedAnchor] until its folders are listed again. Main-thread confined.
 */
@Stable
internal class FileExplorerState(
  val root: String,
  private val files: WorkspaceFolders,
  initiallyExpanded: Set<String> = emptySet(),
  savedAnchor: ScrollAnchor? = null,
) {
  var directories by mutableStateOf(emptyMap<String, List<FileEntry>>())
    private set
  var expanded by mutableStateOf(initiallyExpanded)
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

  /** The tree's list, kept with it so each workspace keeps its own scroll position. */
  val list = LazyListState()

  private var pendingAnchor by mutableStateOf(savedAnchor)

  /** A [refresh] has finished listing every expanded folder, so the rows [restoreScroll] gets are the whole tree. */
  private var relisted = false

  /** Restored with a scroll position that [restoreScroll] hasn't applied yet; the list stays hidden until it has. */
  val restoringScroll: Boolean get() = pendingAnchor != null

  /**
   * Scrolls the list back to where it was saved, as near as [rows], the tree as listed again, allow. Waits for a
   * [refresh] to finish: one cancelled part way, as closing the explorer does, leaves rows missing that the saved
   * position may be in.
   */
  fun restoreScroll(rows: List<ExplorerRow.Entry>) {
    if (!relisted) return
    val anchor = pendingAnchor ?: return
    pendingAnchor = null
    val position = listPosition(rows, anchor)
    list.requestScrollToItem(position.index, position.offset)
  }

  /** The row at the top of the list, or null at the very top, which stays the top whatever is added above. */
  fun scrollAnchor(): ScrollAnchor? {
    pendingAnchor?.let { return it }
    val first = list.firstVisibleItemIndex
    val offset = list.firstVisibleItemScrollOffset
    if (first == 0 && offset == 0) return null
    val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index >= first && it.key != NEW_FOLDER_KEY }
    val path = item?.key as? String ?: return null
    return ScrollAnchor(path, if (item.index == first) offset else 0)
  }

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
        pendingAnchor = null
        return
      }
      for ((path, error) in failures) if (error != null) collapse(path)
      relisted = true
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
