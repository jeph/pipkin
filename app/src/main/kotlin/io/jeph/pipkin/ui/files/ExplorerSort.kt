package io.jeph.pipkin.ui.files

import io.jeph.pipkin.host.FileEntry

/** What the explorer orders each folder by; [descendingFirst] is the direction Paseo uses for it. */
internal enum class SortKey(val descendingFirst: Boolean) {
  Name(descendingFirst = false),
  Size(descendingFirst = true),
  Date(descendingFirst = true),
}

/** The explorer's order: by [key], [descending] or not. Folders always come before files, as in Paseo. */
internal data class ExplorerSort(val key: SortKey, val descending: Boolean) {
  /** The next key, in the direction Paseo sorts it: names A to Z, the largest and the newest first. */
  fun next(): ExplorerSort {
    val key = SortKey.entries[(key.ordinal + 1) % SortKey.entries.size]
    return ExplorerSort(key, key.descendingFirst)
  }

  fun reversed(): ExplorerSort = copy(descending = !descending)

  companion object {
    val Default = ExplorerSort(SortKey.Name, descending = false)
  }
}

/**
 * [entries] as the host lists them (folders first, names in natural order: `f2` before `f10`) in [sort]'s order,
 * folders still first. Equal sizes or dates keep name order. Under [SortKey.Size] folders go by name, since a folder's
 * own size says nothing about what's in it.
 */
internal fun sortEntries(entries: List<FileEntry>, sort: ExplorerSort): List<FileEntry> {
  val (folders, files) = entries.partition { it.isDirectory }
  val folderSort = if (sort.key == SortKey.Size) ExplorerSort.Default else sort
  return ordered(folders, folderSort) + ordered(files, sort)
}

private fun ordered(entries: List<FileEntry>, sort: ExplorerSort): List<FileEntry> = when (sort.key) {
  SortKey.Name -> if (sort.descending) entries.asReversed() else entries
  SortKey.Size -> if (sort.descending) entries.sortedByDescending { it.size } else entries.sortedBy { it.size }
  SortKey.Date -> if (sort.descending) entries.sortedByDescending { it.modified } else entries.sortedBy { it.modified }
}
