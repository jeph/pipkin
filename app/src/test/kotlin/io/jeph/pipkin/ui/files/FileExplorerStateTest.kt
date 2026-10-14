package io.jeph.pipkin.ui.files

import androidx.compose.runtime.saveable.SaverScope
import io.jeph.pipkin.host.FileEntry
import io.jeph.pipkin.host.FileError
import io.jeph.pipkin.host.WorkspaceFolders
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileExplorerStateTest {
  private fun folder(path: String) =
    FileEntry(path.substringAfterLast('/'), path, isDirectory = true, size = 0, modified = 0)

  private fun file(path: String) =
    FileEntry(path.substringAfterLast('/'), path, isDirectory = false, size = 0, modified = 0)

  /**
   * A workspace whose folders are [folders]; listing any other path fails as the host does for a missing one, and
   * listing a [stalled] one never finishes.
   */
  private class Folders(val folders: Map<String, List<FileEntry>>) : WorkspaceFolders {
    var stalled = emptySet<String>()

    override suspend fun list(root: String, path: String): List<FileEntry> {
      if (path in stalled) awaitCancellation()
      return folders[path] ?: throw FileError("This file or folder no longer exists.")
    }

    override suspend fun createFolder(root: String, parent: String, name: String) = error("unused")
  }

  private val workspace = mapOf(
    ROOT_FOLDER to listOf(folder("src"), folder("docs"), file(".env"), file("README.md")),
    "src" to listOf(folder("src/main"), file("src/.cache"), file("src/app.ts")),
    "src/main" to listOf(file("src/main/index.ts"), file("src/main/util.ts")),
    "docs" to listOf(file("docs/guide.md")),
  )

  private fun restored(folders: Folders, expanded: Set<String>, anchor: ScrollAnchor?) =
    FileExplorerState("/work", folders, initiallyExpanded = expanded, savedAnchor = anchor)

  /** Lists [state] again as the explorer does on opening, then scrolls it back; returns its rows. */
  private fun reopen(state: FileExplorerState, showHidden: Boolean = true): List<String> {
    runBlocking { state.refresh() }
    val rows = explorerRows(state.directories, state.expanded, showHidden, ExplorerSort.Default)
    state.restoreScroll(rows)
    return rows.map { it.entry.path }
  }

  private fun FileExplorerState.position() = ListPosition(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)

  @Test
  fun aRestoredTreeReopensItsFoldersScrolledBackToItsTopRow() {
    val state = restored(Folders(workspace), setOf("src", "src/main"), ScrollAnchor("src/main/util.ts", 12))
    assertTrue(state.restoringScroll)

    val rows = reopen(state)

    assertEquals(setOf("src", "src/main"), state.expanded)
    assertEquals(ListPosition(rows.indexOf("src/main/util.ts"), 12), state.position())
    assertFalse(state.restoringScroll)
  }

  @Test
  fun closingTheExplorerBeforeItsFoldersAreListedKeepsTheSavedPositionForNextTime() {
    val folders = Folders(workspace).apply { stalled = setOf("src/main") }
    val state = restored(folders, setOf("src", "src/main"), ScrollAnchor("src/main/util.ts", 12))
    runBlocking {
      val refresh = launch { state.refresh() }
      while (ROOT_FOLDER !in state.directories) yield()
      refresh.cancelAndJoin()
    }
    state.restoreScroll(explorerRows(state.directories, state.expanded, showHidden = true, ExplorerSort.Default))

    assertTrue(state.restoringScroll)

    folders.stalled = emptySet()
    val rows = reopen(state)

    assertEquals(ListPosition(rows.indexOf("src/main/util.ts"), 12), state.position())
  }

  @Test
  fun anExpandedFolderDeletedSinceSavingCollapsesAndTheListScrollsToItsNearestFolder() {
    val folders = Folders(workspace - "src/main" + ("src" to listOf(file("src/app.ts"))))
    val state = restored(folders, setOf("src", "src/main"), ScrollAnchor("src/main/util.ts", 12))

    val rows = reopen(state)

    assertEquals(setOf("src"), state.expanded)
    assertEquals(ListPosition(rows.indexOf("src"), 0), state.position())
  }

  @Test
  fun anExpandedFolderNowAFileCollapses() {
    val folders = Folders(workspace - "docs" + (ROOT_FOLDER to listOf(folder("src"), file("docs"))))
    val state = restored(folders, setOf("docs"), anchor = null)

    reopen(state)

    assertEquals(emptySet<String>(), state.expanded)
  }

  @Test
  fun filesAddedAboveTheTopRowKeepItAtTheTop() {
    val added = listOf(folder("assets"), folder("build")) + workspace.getValue(ROOT_FOLDER)
    val state = restored(Folders(workspace + (ROOT_FOLDER to added)), setOf("docs"), ScrollAnchor("docs/guide.md", 4))

    val rows = reopen(state)

    assertEquals(ListPosition(rows.indexOf("docs/guide.md"), 4), state.position())
  }

  @Test
  fun aTopRowNowHiddenFallsBackToItsFolder() {
    val state = restored(Folders(workspace), setOf("src"), ScrollAnchor("src/.cache", 7))

    val rows = reopen(state, showHidden = false)

    assertEquals(ListPosition(rows.indexOf("src"), 0), state.position())
  }

  @Test
  fun aTopRowWithNoFolderLeftGoesToTheTop() {
    val state = restored(Folders(workspace), setOf("old", "old/deep"), ScrollAnchor("old/deep/notes.md", 30))

    reopen(state)

    assertEquals(emptySet<String>(), state.expanded)
    assertEquals(ListPosition(0, 0), state.position())
  }

  @Test
  fun aWorkspaceFolderGoneDropsTheRestoredTreeForItsFailure() {
    val state = restored(Folders(emptyMap()), setOf("src"), ScrollAnchor("src/app.ts", 3))

    runBlocking { state.refresh() }

    assertEquals("This file or folder no longer exists.", state.failure)
    assertEquals(emptySet<String>(), state.expanded)
    assertFalse(state.restoringScroll)
  }

  @Test
  fun eachWorkspacesTreeSavesAndRestoresItsExpandedFoldersAndTopRow() {
    val saver = ExplorerTrees.saver(Folders(workspace))
    val saved = arrayListOf(
      arrayListOf<Any?>("/work", arrayListOf("src"), "src/app.ts", 12),
      arrayListOf<Any?>("/other", arrayListOf<String>(), null, null),
    )

    val trees = saver.restore(saved)!!

    assertEquals(setOf("src"), trees["/work"].expanded)
    assertEquals(ScrollAnchor("src/app.ts", 12), trees["/work"].scrollAnchor())
    assertEquals(emptySet<String>(), trees["/other"].expanded)
    assertNull(trees["/other"].scrollAnchor())
    assertEquals(saved.toSet(), with(saver) { SaverScope { true }.save(trees) }!!.toSet())
  }
}
