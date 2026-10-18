package io.jeph.pipkin.ui.git

import io.jeph.pipkin.host.GitChanges
import io.jeph.pipkin.host.GitDetection
import io.jeph.pipkin.host.GitDiffMode
import java.math.BigDecimal
import java.math.RoundingMode

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

/** The unit [compactScale] shortens a count to. */
internal enum class CountUnit { One, Thousand, Million }

/** Paseo's compact diff count, to a tenth of its unit: `999`, `1.2` thousand, `12.3` thousand, `1.5` million. */
internal fun compactScale(value: Int): Pair<BigDecimal, CountUnit> {
  if (value < 1_000) return BigDecimal.valueOf(value.toLong()) to CountUnit.One
  val thousands = BigDecimal.valueOf(value.toLong(), 3).setScale(1, RoundingMode.HALF_UP)
  if (thousands < BigDecimal.valueOf(1_000)) return thousands.stripTrailingZeros() to CountUnit.Thousand
  val millions = BigDecimal.valueOf(value.toLong(), 6).setScale(1, RoundingMode.HALF_UP)
  return millions.stripTrailingZeros() to CountUnit.Million
}
