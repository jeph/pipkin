package io.jeph.pipkin.ui.files

import io.jeph.pipkin.host.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerRowsTest {
  private fun folder(path: String) =
    FileEntry(path.substringAfterLast('/'), path, isDirectory = true, size = 0, modified = 0)

  private fun file(path: String) =
    FileEntry(path.substringAfterLast('/'), path, isDirectory = false, size = 0, modified = 0)

  private val directories = mapOf(
    ROOT_FOLDER to listOf(folder(".git"), folder("src"), folder("docs"), file(".env"), file("README.md")),
    ".git" to listOf(file(".git/HEAD")),
    "src" to listOf(folder("src/main"), file("src/.cache"), file("src/app.ts")),
    "src/main" to listOf(file("src/main/index.ts")),
    "docs" to listOf(file("docs/guide.md")),
  )

  private fun rows(expanded: Set<String>, showHidden: Boolean) =
    explorerRows(directories, expanded, showHidden).map { "${"  ".repeat(it.depth)}${it.entry.name}" }

  @Test
  fun anExpandedFolderListsItsEntriesRightUnderItOneLevelDeeper() {
    assertEquals(
      listOf(".git", "src", "  main", "    index.ts", "  .cache", "  app.ts", "docs", ".env", "README.md"),
      rows(expanded = setOf("src", "src/main"), showHidden = true),
    )
  }

  @Test
  fun aCollapsedFolderHidesWhatWasListedInsideIt() {
    assertEquals(
      listOf(".git", "src", "docs", ".env", "README.md"),
      rows(expanded = setOf("src/main"), showHidden = true),
    )
  }

  @Test
  fun hidingDotEntriesHidesThemAtEveryDepthWithTheirContents() {
    assertEquals(
      listOf("src", "  main", "  app.ts", "docs", "README.md"),
      rows(expanded = setOf(".git", "src"), showHidden = false),
    )
  }

  @Test
  fun nothingShowsBeforeTheTopFolderIsListed() {
    assertEquals(emptyList<ExplorerRow.Entry>(), explorerRows(emptyMap(), setOf("src"), showHidden = true))
  }
}
