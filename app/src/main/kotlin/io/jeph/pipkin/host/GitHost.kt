package io.jeph.pipkin.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The two comparisons the Changes tab offers, by their `git` host names. */
internal enum class GitDiffMode(val wire: String) {
  /** HEAD against the working tree, untracked files included. */
  Uncommitted("uncommitted"),

  /** The merge-base with the base branch against HEAD. */
  Base("base"),
}

/** Whether a workspace root holds a repository ([repo]) and Termux has git ([git]). */
internal data class GitDetection(val repo: Boolean, val git: Boolean) {
  val available: Boolean get() = repo && git
}

internal enum class GitChange { Added, Deleted, Modified, Renamed, Copied }

internal enum class GitFileKind { Text, Binary, TooLarge }

/** One hunk; each of [lines] keeps its prefix: ` ` context, `+` added, `-` removed, `\` a no-newline marker. */
internal class GitHunk(val header: String, val oldStart: Int, val newStart: Int, val lines: List<String>)

/** A changed file at its new [path]; the viewer shows only that one for a rename or copy, as Paseo does. */
internal class GitFile(
  val path: String,
  val change: GitChange,
  val additions: Int,
  val deletions: Int,
  val kind: GitFileKind,
  val hunks: List<GitHunk>,
)

/** A diff's files, or none and [tooLarge] when the whole diff is too large to send. */
internal class GitDiff(val files: List<GitFile>, val tooLarge: Boolean)

/** A comparison's diff; [branch] is null while HEAD is detached, [baseRef] while there's no base branch. */
internal class GitChanges(val fingerprint: String, val branch: String?, val baseRef: String?, val diff: GitDiff)

/** A commit; [authorTime] in epoch seconds. */
internal class GitCommit(val sha: String, val subject: String, val authorTime: Long) {
  val shortSha: String get() = sha.take(SHORT_SHA_LENGTH)
}

/**
 * The commits ahead of the comparison base [baseRef], newest first: the host's first ones, out of [total] in all.
 */
internal class GitCommits(val fingerprint: String, val baseRef: String?, val commits: List<GitCommit>, val total: Int)

internal enum class GitErrorCode { NotRepo, NoGit, Failed, CommitNotFound }

/** A `git` host reply: what was asked for, [Unchanged] since the fingerprint sent as known, or why git failed. */
internal sealed interface GitReply<out T> {
  data object Unchanged : GitReply<Nothing>

  class Loaded<T>(val value: T) : GitReply<T>

  /** [message] is why, in the app's language, or empty when git gave no reason. */
  class Failed(val code: GitErrorCode, private val reason: HostText) : GitReply<Nothing> {
    val message: String get() = reason.resolve()
  }
}

/**
 * The workspaces' repositories through one long-lived `git` host shared by every workspace, so each reload doesn't
 * pay for starting Termux. Replies are parsed off the main thread. Requests throw [HostFailure] or
 * [HostRejected] when the host can't run them; git's own failures come back as [GitReply.Failed]. Main-thread
 * confined.
 */
internal class GitHost(hostClient: HostClient, scope: CoroutineScope) {
  private val host = SharedHost(scope, IDLE_MILLIS, hostClient::openGit)

  /** Whether [root] is a repository git can use; git reads a [trusted] one to check, an untrusted one only by path. */
  suspend fun detect(root: String, trusted: Boolean): GitDetection {
    val reply = host.request(JSONObject().put("type", DETECT).put("root", root).put("trusted", trusted), DETECT_TIMEOUT)
    return parseGitDetection(reply)
  }

  /** [root]'s [mode] diff; [known], the fingerprint of the diff shown, makes an unchanged one [GitReply.Unchanged]. */
  suspend fun changes(
    root: String,
    mode: GitDiffMode,
    ignoreWhitespace: Boolean,
    known: String?,
  ): GitReply<GitChanges> {
    val message = JSONObject()
      .put("type", CHANGES)
      .put("root", root)
      .put("mode", mode.wire)
      .put("ignoreWhitespace", ignoreWhitespace)
    if (known != null) message.put("known", known)
    val reply = host.request(message, DIFF_TIMEOUT)
    return withContext(Dispatchers.Default) { parseGitChanges(reply) }
  }

  /** [root]'s commits ahead of its comparison base; [known] works as in [changes]. */
  suspend fun commits(root: String, known: String?): GitReply<GitCommits> {
    val message = JSONObject().put("type", COMMITS).put("root", root)
    if (known != null) message.put("known", known)
    val reply = host.request(message, DIFF_TIMEOUT)
    return withContext(Dispatchers.Default) { parseGitCommits(reply) }
  }

  /** What commit [sha] changed against its first parent. */
  suspend fun commitDiff(root: String, sha: String, ignoreWhitespace: Boolean): GitReply<GitDiff> {
    val message = JSONObject()
      .put("type", COMMIT_DIFF)
      .put("root", root)
      .put("sha", sha)
      .put("ignoreWhitespace", ignoreWhitespace)
    val reply = host.request(message, DIFF_TIMEOUT)
    return withContext(Dispatchers.Default) { parseGitCommitDiff(reply) }
  }

  private companion object {
    const val DETECT = "pipkin_git_detect"
    const val CHANGES = "pipkin_git_changes"
    const val COMMITS = "pipkin_git_commits"
    const val COMMIT_DIFF = "pipkin_git_commit_diff"

    /** Detection runs at most one `git rev-parse`, which the host gives 30 seconds. */
    const val DETECT_TIMEOUT = 40_000L

    /** A diff runs several git commands, each of which the host gives 30 seconds. */
    const val DIFF_TIMEOUT = 120_000L

    /** Long enough that reading diffs in a sitting, and refreshing them, reuses one host. */
    const val IDLE_MILLIS = 120_000L
  }
}

/** Git's own abbreviation length, which a short SHA in Paseo's commit rows has too. */
private const val SHORT_SHA_LENGTH = 7

internal fun parseGitDetection(reply: JSONObject) =
  GitDetection(repo = reply.optBoolean("repo"), git = reply.optBoolean("git"))

internal fun parseGitChanges(reply: JSONObject): GitReply<GitChanges> = parseReply(reply) {
  GitChanges(
    fingerprint = reply.optString("fingerprint"),
    branch = reply.optNullableString("branch"),
    baseRef = reply.optNullableString("baseRef"),
    diff = parseGitDiff(reply),
  )
}

internal fun parseGitCommits(reply: JSONObject): GitReply<GitCommits> = parseReply(reply) {
  val commits = reply.optJSONArray("commits").objects().map {
    GitCommit(sha = it.optString("sha"), subject = it.optString("subject"), authorTime = it.optLong("authorTime"))
  }
  GitCommits(
    fingerprint = reply.optString("fingerprint"),
    baseRef = reply.optNullableString("baseRef"),
    commits = commits,
    // Never fewer than were sent, so a reply without a total reads as the whole list.
    total = maxOf(reply.optInt("total"), commits.size),
  )
}

internal fun parseGitCommitDiff(reply: JSONObject): GitReply<GitDiff> = parseReply(reply) { parseGitDiff(reply) }

/** [reply] as a failure when it carries `code` or `error` (and nothing else is sent then), unchanged, or [loaded]. */
private fun <T> parseReply(reply: JSONObject, loaded: () -> T): GitReply<T> {
  val code = reply.optNullableString("code")
  val error = reply.optNullableString("error")
  if (code != null || error != null) {
    return GitReply.Failed(errorCode(code), HostText.of(error.orEmpty(), reply.optJSONObject("errorI18n")))
  }
  if (reply.optBoolean("unchanged")) return GitReply.Unchanged
  return GitReply.Loaded(loaded())
}

private fun errorCode(code: String?): GitErrorCode = when (code) {
  "not_repo" -> GitErrorCode.NotRepo
  "no_git" -> GitErrorCode.NoGit
  "commit_not_found" -> GitErrorCode.CommitNotFound
  else -> GitErrorCode.Failed
}

private fun parseGitDiff(reply: JSONObject): GitDiff {
  if (reply.optBoolean("tooLarge")) return GitDiff(emptyList(), tooLarge = true)
  return GitDiff(reply.optJSONArray("files").objects().map(::parseGitFile), tooLarge = false)
}

private fun parseGitFile(file: JSONObject) = GitFile(
  path = file.optString("path"),
  change = when (file.optString("change")) {
    "added" -> GitChange.Added
    "deleted" -> GitChange.Deleted
    "renamed" -> GitChange.Renamed
    "copied" -> GitChange.Copied
    else -> GitChange.Modified
  },
  additions = file.optInt("additions"),
  deletions = file.optInt("deletions"),
  kind = when (file.optString("kind")) {
    "binary" -> GitFileKind.Binary
    "too_large" -> GitFileKind.TooLarge
    else -> GitFileKind.Text
  },
  hunks = file.optJSONArray("hunks").objects().map(::parseGitHunk),
)

private fun parseGitHunk(hunk: JSONObject) = GitHunk(
  header = hunk.optString("header"),
  oldStart = hunk.optInt("oldStart"),
  newStart = hunk.optInt("newStart"),
  // jsdiff splits a CRLF file's lines at the LF only, leaving each one its CR.
  lines = hunk.optJSONArray("lines").strings().map { it.removeSuffix("\r") },
)

/** The string at [key], or null when it's missing or JSON null (which [JSONObject.optString] reads as "null"). */
private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key)
