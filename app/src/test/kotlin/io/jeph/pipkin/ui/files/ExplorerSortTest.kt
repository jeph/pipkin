package io.jeph.pipkin.ui.files

import io.jeph.pipkin.host.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerSortTest {
  private fun folder(name: String, size: Long, modified: Long) =
    FileEntry(name, name, isDirectory = true, size = size, modified = modified)

  private fun file(name: String, size: Long, modified: Long) =
    FileEntry(name, name, isDirectory = false, size = size, modified = modified)

  // As the host lists them: folders first, each group in natural name order.
  private val listed = listOf(
    folder("a-dir", size = 9000, modified = 1),
    folder("b-dir", size = 10, modified = 3),
    file("f2.txt", size = 50, modified = 20),
    file("f10.txt", size = 5, modified = 30),
    file("g.txt", size = 50, modified = 10),
  )

  private fun names(sort: ExplorerSort) = sortEntries(listed, sort).map { it.name }

  @Test
  fun eachKeyStartsInPaseosDirectionWithFoldersFirst() {
    val byName = ExplorerSort.Default
    val bySize = byName.next()
    val byDate = bySize.next()
    assertEquals(listOf("a-dir", "b-dir", "f2.txt", "f10.txt", "g.txt"), names(byName))
    assertEquals(ExplorerSort(SortKey.Size, descending = true), bySize)
    assertEquals(listOf("a-dir", "b-dir", "f2.txt", "g.txt", "f10.txt"), names(bySize))
    assertEquals(ExplorerSort(SortKey.Date, descending = true), byDate)
    assertEquals(listOf("b-dir", "a-dir", "f10.txt", "f2.txt", "g.txt"), names(byDate))
    assertEquals(ExplorerSort.Default, byDate.next())
  }

  @Test
  fun reversingFlipsTheKeyButKeepsFoldersFirstAndTiesInNameOrder() {
    assertEquals(
      listOf("b-dir", "a-dir", "g.txt", "f10.txt", "f2.txt"),
      names(ExplorerSort.Default.reversed()),
    )
    assertEquals(
      listOf("a-dir", "b-dir", "f10.txt", "f2.txt", "g.txt"),
      names(ExplorerSort(SortKey.Size, descending = false)),
    )
  }

  @Test
  fun cyclingTheKeyForgetsAReversedDirection() {
    assertEquals(ExplorerSort(SortKey.Size, descending = true), ExplorerSort.Default.reversed().next())
  }
}
