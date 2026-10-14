package io.jeph.pipkin.ui.git

import io.jeph.pipkin.host.GitChange
import io.jeph.pipkin.host.GitChanges
import io.jeph.pipkin.host.GitDetection
import io.jeph.pipkin.host.GitDiff
import io.jeph.pipkin.host.GitDiffMode
import io.jeph.pipkin.host.GitFile
import io.jeph.pipkin.host.GitFileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitExplorerTest {
  private val available = GitDetection(repo = true, git = true)
  private val notRepository = GitDetection(repo = false, git = true)
  private val noGit = GitDetection(repo = true, git = false)
  private val neither = GitDetection(repo = false, git = false)

  private fun changes(files: List<GitFile> = emptyList(), tooLarge: Boolean = false) =
    GitChanges("f", "feature", "main", GitDiff(files, tooLarge))

  @Test
  fun `Changes shows by default and while remembered, Files while remembered or Changes is unavailable`() {
    assertEquals(ExplorerTab.Changes, shownExplorerTab(remembered = null, changesAvailable = true))
    assertEquals(ExplorerTab.Changes, shownExplorerTab(ExplorerTab.Changes, changesAvailable = true))
    assertEquals(ExplorerTab.Files, shownExplorerTab(ExplorerTab.Files, changesAvailable = true))
    assertEquals(ExplorerTab.Files, shownExplorerTab(remembered = null, changesAvailable = false))
    assertEquals(ExplorerTab.Files, shownExplorerTab(ExplorerTab.Changes, changesAvailable = false))
  }

  @Test
  fun `losing the repository or git while Changes was available says which`() {
    assertEquals(GitLoss.NotRepository, gitLoss(available, notRepository))
    assertEquals(GitLoss.GitMissing, gitLoss(available, noGit))
    assertEquals(GitLoss.GitMissing, gitLoss(available, neither))
  }

  @Test
  fun `no snackbar when Changes stays, comes back, or never showed`() {
    assertNull(gitLoss(available, available))
    assertNull(gitLoss(notRepository, available))
    assertNull(gitLoss(null, notRepository))
    assertNull(gitLoss(null, noGit))
    assertNull(gitLoss(notRepository, noGit))
  }

  @Test
  fun `a workspace that went away and came back is lost again only once it's shown again`() {
    val detections = listOf(available, notRepository, notRepository, available, noGit)

    val losses = detections.zipWithNext { before, after -> gitLoss(before, after) }

    assertEquals(listOf(GitLoss.NotRepository, null, null, GitLoss.GitMissing), losses)
  }

  @Test
  fun `the first comparison is Uncommitted with working tree changes and Committed without`() {
    val changed = GitFile("a.kt", GitChange.Modified, 1, 0, GitFileKind.Text, emptyList())

    assertEquals(GitDiffMode.Uncommitted, defaultDiffMode(changes(listOf(changed))))
    assertEquals(GitDiffMode.Uncommitted, defaultDiffMode(changes(tooLarge = true)))
    assertEquals(GitDiffMode.Base, defaultDiffMode(changes()))
  }

  @Test
  fun `refs are labelled as Paseo labels them`() {
    assertEquals("main", branchLabel("main"))
    assertEquals("main", branchLabel("origin/main"))
    assertEquals("main", branchLabel("refs/heads/main"))
    assertEquals("main", branchLabel("refs/remotes/origin/main"))
    assertEquals("upstream/main", branchLabel("refs/remotes/upstream/main"))
    assertNull(branchLabel("HEAD"))
    assertNull(branchLabel(" "))
    assertNull(branchLabel(null))
  }

  @Test
  fun `Committed is described as branch to base only when both are known and differ`() {
    assertEquals("feature -> main", committedDescription("feature", "origin/main"))
    assertNull(committedDescription("main", "origin/main"))
    assertNull(committedDescription(null, "main"))
    assertNull(committedDescription("feature", null))
  }

  @Test
  fun `counts shorten like Paseo's compact numbers`() {
    assertEquals("999", compactCount(999))
    assertEquals("1k", compactCount(1_000))
    assertEquals("1.2k", compactCount(1_234))
    assertEquals("12.3k", compactCount(12_345))
    assertEquals("1m", compactCount(999_999))
    assertEquals("1.5m", compactCount(1_500_000))
  }
}
