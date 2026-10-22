package io.jeph.pipkin.ui.git

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import io.jeph.pipkin.host.GitDiff
import io.jeph.pipkin.host.GitFile
import io.jeph.pipkin.host.GitFileKind
import io.jeph.pipkin.host.GitHunk
import io.jeph.pipkin.ui.theme.PipkinColors

internal enum class DiffLineKind { Context, Added, Removed }

/** One row of a file's diff body, in the order the document shows them. */
internal sealed interface DiffRow {
  /** A hunk's `@@ -a,b +c,d @@` line. */
  class HunkHeader(val header: String) : DiffRow

  /**
   * A line of a hunk without its prefix. [number] is the old file's line number for a removed line, the new file's
   * for an added or context one, and null for a no-newline marker. [sideLine] is where the line sits in its side's
   * text ([DiffSection.oldSide] for a removed line, [DiffSection.newSide] otherwise), or -1 for a marker.
   */
  class Line(val kind: DiffLineKind, val number: Int?, val text: String, val sideLine: Int) : DiffRow

  /** In place of the hunks of a binary file or one whose diff is too large to send. */
  class Placeholder(val kind: GitFileKind) : DiffRow
}

/** A side's highlighted lines, coloured for [theme]'s palette. */
internal class SideHighlight(
  val theme: PipkinColors,
  val oldLines: List<AnnotatedString>,
  val newLines: List<AnnotatedString>,
)

/**
 * One file of a [DiffDocument]: its rows, and its hunks put back together as the old and new side texts, which are
 * highlighted whole, as Paseo does, so a line's colours follow the code around it rather than that line alone.
 */
@Stable
internal class DiffSection(
  val file: GitFile,
  val rows: List<DiffRow>,
  val oldSide: String,
  val newSide: String,
  val maxLineNumber: Int,
) {
  val name: String get() = file.path.substringAfterLast('/')

  /** The folder holding the file, empty at the top of the repository. */
  val folder: String get() = file.path.substringBeforeLast('/', "")

  /** Set once the sides are highlighted off the main thread; until then, or without a grammar, lines show plain. */
  var highlight by mutableStateOf<SideHighlight?>(null)
}

/**
 * A diff as one scrolling document of file sections in Paseo's tree order (see [treeOrder]); none and [tooLarge] when
 * it couldn't be sent.
 */
internal class DiffDocument(val sections: List<DiffSection>, val tooLarge: Boolean) {
  val additions: Int = sections.sumOf { it.file.additions }
  val deletions: Int = sections.sumOf { it.file.deletions }

  companion object {
    /**
     * [diff]'s document. A file whose sides read the same as in [previous] keeps its highlighting, so a refresh that
     * changed other files doesn't flash this one plain.
     */
    fun build(diff: GitDiff, previous: DiffDocument? = null): DiffDocument {
      val previousSections = previous?.sections.orEmpty().associateBy { it.file.path }
      val sections = diff.files.sortedWith(compareBy(treeOrder) { it.path }).map { file ->
        val section = section(file)
        val before = previousSections[file.path]
        if (before != null && before.oldSide == section.oldSide && before.newSide == section.newSide) {
          section.highlight = before.highlight
        }
        section
      }
      return DiffDocument(sections, diff.tooLarge)
    }
  }
}

/**
 * Paseo's changed-files tree order, flattened: at each folder level, folders before files, then names compared by
 * their UTF-16 code units as JavaScript's `<` does, so the order doesn't shift with the device's locale.
 */
internal val treeOrder = Comparator<String> { a, b ->
  val left = a.split('/')
  val right = b.split('/')
  var level = 0
  while (level < left.size && level < right.size) {
    val leftIsFolder = level < left.lastIndex
    val rightIsFolder = level < right.lastIndex
    if (leftIsFolder != rightIsFolder) return@Comparator if (leftIsFolder) -1 else 1
    val byName = left[level].compareTo(right[level])
    if (byName != 0) return@Comparator byName
    level++
  }
  left.size - right.size
}

private fun section(file: GitFile): DiffSection {
  if (file.kind != GitFileKind.Text) {
    return DiffSection(file, listOf(DiffRow.Placeholder(file.kind)), oldSide = "", newSide = "", maxLineNumber = 0)
  }
  val sides = Sides()
  val rows = mutableListOf<DiffRow>()
  for (hunk in file.hunks) {
    rows += DiffRow.HunkHeader(hunk.header)
    addLines(hunk, sides, rows)
  }
  return DiffSection(file, rows, sides.old.text(), sides.new.text(), sides.maxLineNumber)
}

/** [hunk]'s lines as rows, numbered from its starts, each also added to the side text it belongs to. */
private fun addLines(hunk: GitHunk, sides: Sides, rows: MutableList<DiffRow>) {
  var oldNumber = hunk.oldStart
  var newNumber = hunk.newStart
  for (line in hunk.lines) {
    // Paseo leaves out git's `\ No newline at end of file` markers.
    if (line.startsWith('\\')) continue
    val text = if (line.isEmpty()) "" else line.substring(1)
    val row = when (line.firstOrNull()) {
      '+' -> DiffRow.Line(DiffLineKind.Added, newNumber++, text, sides.new.add(text))

      '-' -> DiffRow.Line(DiffLineKind.Removed, oldNumber++, text, sides.old.add(text))

      // A context line is on both sides; it's coloured from the new one. An empty line has lost its space prefix.
      else -> {
        sides.old.add(text)
        oldNumber++
        DiffRow.Line(DiffLineKind.Context, newNumber++, text, sides.new.add(text))
      }
    }
    rows += row
  }
  // The last numbers handed out, one past each side's end, bound the gutter's width.
  sides.maxLineNumber = maxOf(sides.maxLineNumber, oldNumber - 1, newNumber - 1)
}

private class Sides {
  val old = SideText()
  val new = SideText()
  var maxLineNumber = 0
}

/** A side's lines from every hunk, one after another. */
private class SideText {
  private val builder = StringBuilder()
  private var count = 0

  /** Appends [line]; returns its index among the side's lines. */
  fun add(line: String): Int {
    if (count > 0) builder.append('\n')
    builder.append(line)
    return count++
  }

  fun text(): String = builder.toString()
}
