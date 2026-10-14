package io.jeph.pipkin.ui.git

import androidx.compose.ui.text.AnnotatedString
import io.jeph.pipkin.host.GitChange
import io.jeph.pipkin.host.GitDiff
import io.jeph.pipkin.host.GitFile
import io.jeph.pipkin.host.GitFileKind
import io.jeph.pipkin.host.GitHunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DiffDocumentTest {
  private fun file(path: String, vararg hunks: GitHunk, kind: GitFileKind = GitFileKind.Text) =
    GitFile(path, GitChange.Modified, additions = 0, deletions = 0, kind = kind, hunks = hunks.toList())

  private fun document(vararg files: GitFile) = DiffDocument.build(GitDiff(files.toList(), tooLarge = false))

  /** Each line row as `kind number text sideLine`, hunk headers as their header. */
  private fun rows(section: DiffSection): List<String> = section.rows.map { row ->
    when (row) {
      is DiffRow.HunkHeader -> row.header
      is DiffRow.Line -> "${row.kind} ${row.number} ${row.text} ${row.sideLine}"
      is DiffRow.Placeholder -> "placeholder ${row.kind}"
    }
  }

  @Test
  fun `removed lines take the old number and added and context lines the new one`() {
    val hunk = GitHunk("@@ -10,3 +20,3 @@", oldStart = 10, newStart = 20, lines = listOf(" a", "-b", "+B", " c"))

    val section = document(file("f.kt", hunk)).sections.single()

    assertEquals(
      listOf(
        "@@ -10,3 +20,3 @@",
        "Context 20 a 0",
        "Removed 11 b 1",
        "Added 21 B 1",
        "Context 22 c 2",
      ),
      rows(section),
    )
    assertEquals(22, section.maxLineNumber)
  }

  @Test
  fun `no-newline markers are left out without advancing either side`() {
    val hunk = GitHunk(
      "@@ -1,2 +1,2 @@",
      oldStart = 1,
      newStart = 1,
      lines = listOf(" same", "-old", "\\ No newline at end of file", "+new", "\\ No newline at end of file"),
    )

    val section = document(file("f.txt", hunk)).sections.single()

    assertEquals(
      listOf(
        "@@ -1,2 +1,2 @@",
        "Context 1 same 0",
        "Removed 2 old 1",
        "Added 2 new 1",
      ),
      rows(section),
    )
    assertEquals("same\nold", section.oldSide)
    assertEquals("same\nnew", section.newSide)
  }

  @Test
  fun `numbering restarts at each hunk while the sides join every hunk's lines`() {
    val first = GitHunk("@@ -1,2 +1,2 @@", oldStart = 1, newStart = 1, lines = listOf(" one", "-two", "+TWO"))
    val second = GitHunk("@@ -40 +40,2 @@", oldStart = 40, newStart = 40, lines = listOf(" forty", "+more"))

    val section = document(file("f.kt", first, second)).sections.single()

    assertEquals(
      listOf(
        "@@ -1,2 +1,2 @@",
        "Context 1 one 0",
        "Removed 2 two 1",
        "Added 2 TWO 1",
        "@@ -40 +40,2 @@",
        "Context 40 forty 2",
        "Added 41 more 3",
      ),
      rows(section),
    )
    assertEquals("one\ntwo\nforty", section.oldSide)
    assertEquals("one\nTWO\nforty\nmore", section.newSide)
    assertEquals(41, section.maxLineNumber)
  }

  @Test
  fun `a new file counts from its new start and has an empty old side`() {
    val hunk = GitHunk("@@ -0,0 +1,2 @@", oldStart = 0, newStart = 1, lines = listOf("+a", "+b"))

    val section = document(file("new.kt", hunk)).sections.single()

    assertEquals(listOf("@@ -0,0 +1,2 @@", "Added 1 a 0", "Added 2 b 1"), rows(section))
    assertEquals("", section.oldSide)
  }

  @Test
  fun `an empty context line that lost its space is still context`() {
    val hunk = GitHunk("@@ -1,2 +1,2 @@", oldStart = 1, newStart = 1, lines = listOf("", "+x"))

    val section = document(file("f.kt", hunk)).sections.single()

    assertEquals(listOf("@@ -1,2 +1,2 @@", "Context 1  0", "Added 2 x 1"), rows(section))
  }

  @Test
  fun `binary and too large files show a placeholder instead of hunks`() {
    val sections = document(
      file("image.png", kind = GitFileKind.Binary),
      file("huge.json", kind = GitFileKind.TooLarge),
    ).sections

    assertEquals(listOf("placeholder TooLarge"), rows(sections[0]))
    assertEquals(listOf("placeholder Binary"), rows(sections[1]))
  }

  @Test
  fun `files follow Paseo's tree order, folders before files at each level, names in ASCII order`() {
    val paths = document(
      file("a.txt"),
      file("src/b.kt"),
      file("README.md"),
      file("src/a.kt"),
      file("a/z.txt"),
      file("Build/x.kt"),
      file("src/util/c.kt"),
      file("_notes.md"),
      file("a/b/c.txt"),
    ).sections.map { it.file.path }

    assertEquals(
      listOf(
        "Build/x.kt",
        "a/b/c.txt",
        "a/z.txt",
        "src/util/c.kt",
        "src/a.kt",
        "src/b.kt",
        "README.md",
        "_notes.md",
        "a.txt",
      ),
      paths,
    )
  }

  @Test
  fun `a folder sorts before a file of the same name`() {
    val paths = document(file("lib"), file("lib/x.kt")).sections.map { it.file.path }

    assertEquals(listOf("lib/x.kt", "lib"), paths)
  }

  @Test
  fun `a file whose sides didn't change keeps its highlighting and a changed one loses it`() {
    val same = GitHunk("@@ -1 +1 @@", oldStart = 1, newStart = 1, lines = listOf("-a", "+b"))
    val before = document(file("same.kt", same), file("changed.kt", same))
    val highlight = SideHighlight(dark = true, listOf(AnnotatedString("a")), listOf(AnnotatedString("b")))
    before.sections.forEach { it.highlight = highlight }
    val edited = GitHunk("@@ -1 +1 @@", oldStart = 1, newStart = 1, lines = listOf("-a", "+c"))

    val after = DiffDocument.build(GitDiff(listOf(file("same.kt", same), file("changed.kt", edited)), false), before)

    assertNull(after.sections.first { it.file.path == "changed.kt" }.highlight)
    assertSame(highlight, after.sections.first { it.file.path == "same.kt" }.highlight)
  }
}
