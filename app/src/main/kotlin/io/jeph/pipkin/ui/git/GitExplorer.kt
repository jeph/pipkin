package io.jeph.pipkin.ui.git

import io.jeph.pipkin.host.GitChanges
import io.jeph.pipkin.host.GitDetection
import io.jeph.pipkin.host.GitDiffMode

/** The Explorer panel's tabs, Changes first as in Paseo. */
internal enum class ExplorerTab { Changes, Files }

/**
 * The tab the Explorer shows: the [remembered] one, Changes when there's none, except that Files shows while Changes
 * isn't [changesAvailable]. The remembered tab stays as it was, so Changes comes back once it's available again.
 */
internal fun shownExplorerTab(remembered: ExplorerTab?, changesAvailable: Boolean): ExplorerTab =
  if (changesAvailable && remembered != ExplorerTab.Files) ExplorerTab.Changes else ExplorerTab.Files

/** Why the Changes tab went away, which its snackbar says. */
internal enum class GitLoss { NotRepository, GitMissing }

/**
 * Why the Changes tab went away between detections [before] and [after] of one workspace, or null when it didn't,
 * including when it never showed. A missing git takes the tab whatever the repository, so it's the reason given.
 */
internal fun gitLoss(before: GitDetection?, after: GitDetection): GitLoss? = when {
  before?.available != true || after.available -> null
  !after.git -> GitLoss.GitMissing
  else -> GitLoss.NotRepository
}

/**
 * The comparison a workspace whose user never chose one starts on, from its first uncommitted diff: Uncommitted when
 * the working tree has changes (a diff too large to send has them too), Committed otherwise.
 */
internal fun defaultDiffMode(firstUncommitted: GitChanges): GitDiffMode {
  val diff = firstUncommitted.diff
  return if (diff.tooLarge || diff.files.isNotEmpty()) GitDiffMode.Uncommitted else GitDiffMode.Base
}

/**
 * A branch or ref as Paseo labels it: without `refs/heads/` or `refs/remotes/`, then without `origin/`; null for none
 * or a detached `HEAD`.
 */
internal fun branchLabel(ref: String?): String? {
  val trimmed = ref?.trim()
  if (trimmed.isNullOrEmpty() || trimmed == "HEAD") return null
  val unqualified = if (trimmed.startsWith("refs/heads/")) {
    trimmed.removePrefix("refs/heads/")
  } else {
    trimmed.removePrefix("refs/remotes/")
  }
  return unqualified.removePrefix("origin/")
}

/** Paseo's description of Committed: `branch -> base`, when both are known and differ. */
internal fun committedDescription(branch: String?, baseRef: String?): String? {
  val branchName = branchLabel(branch) ?: return null
  val baseName = branchLabel(baseRef) ?: return null
  return if (branchName == baseName) null else "$branchName -> $baseName"
}

/** Paseo's compact diff count: `999`, `1.2k`, `12.3k`, `1.5m`. */
internal fun compactCount(value: Int): String {
  if (value < 1_000) return value.toString()
  var scaled = value / 1_000.0
  var unit = "k"
  if (Math.round(scaled * 10) >= 10_000) {
    scaled = value / 1_000_000.0
    unit = "m"
  }
  val tenths = Math.round(scaled * 10)
  val text = if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
  return text + unit
}
