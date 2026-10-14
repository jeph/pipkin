package io.jeph.pipkin.ui.git

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.jeph.pipkin.host.GitChanges
import io.jeph.pipkin.host.GitCommits
import io.jeph.pipkin.host.GitDetection
import io.jeph.pipkin.host.GitDiffMode
import io.jeph.pipkin.host.GitHost
import io.jeph.pipkin.host.GitReply
import io.jeph.pipkin.host.HostRejected
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a Changes diff was asked for with. */
internal data class ChangesRequest(val mode: GitDiffMode, val ignoreWhitespace: Boolean)

internal sealed interface ChangesContent {
  /** Nothing loaded yet: the first load is running. */
  data object Loading : ChangesContent

  class Loaded(val request: ChangesRequest, val changes: GitChanges, val document: DiffDocument) : ChangesContent

  /** Git or the host failed; [message] is git's own text, or empty when there is none. */
  class Failed(val message: String) : ChangesContent
}

internal sealed interface CommitsContent {
  data object Loading : CommitsContent

  class Loaded(val commits: GitCommits) : CommitsContent

  data object Failed : CommitsContent
}

/**
 * What the Explorer knows of one workspace's repository at [root]: the last detection, the Changes tab's diff and its
 * Commits section, which files are folded, and the diff's scroll position. A reload keeps what it replaces on screen
 * until its reply arrives, and a reply that says nothing changed leaves the document alone. Reloads of one kind never
 * overlap: one asked for while another runs waits for it. Main-thread confined.
 */
@Stable
internal class GitWorkspace(val root: String, private val git: GitHost) {
  var detection by mutableStateOf<GitDetection?>(null)
    private set

  var changes by mutableStateOf<ChangesContent>(ChangesContent.Loading)
    private set

  /** The diff is reloading; what it replaces stays shown meanwhile. */
  var reloading by mutableStateOf(false)
    private set

  /** The commits are reloading; the ones shown stay meanwhile. */
  var commitsReloading by mutableStateOf(false)
    private set

  /** The comparison chosen for the user, who never chose one, from the first uncommitted diff; null until then. */
  var defaultMode by mutableStateOf<GitDiffMode?>(null)
    private set

  var commits by mutableStateOf<CommitsContent>(CommitsContent.Loading)
    private set

  /** Paths of the files whose sections are folded; every section starts expanded. */
  var collapsed by mutableStateOf(emptySet<String>())

  val list = LazyListState()

  private val detectLock = Mutex()
  private val changesLock = Mutex()
  private val commitsLock = Mutex()

  /**
   * Detects the repository again, reading it with git only when [trusted]; returns why the Changes tab went away, if
   * it did. A detection the host couldn't run keeps the last one.
   */
  suspend fun detect(trusted: Boolean): GitLoss? = detectLock.withLock {
    val before = detection
    val after = try {
      git.detect(root, trusted)
    } catch (_: IOException) {
      return null
    } catch (_: HostRejected) {
      return null
    }
    detection = after
    gitLoss(before, after)
  }

  /**
   * Loads the diff of the comparison [chosen] by the user, else [defaultMode], else Uncommitted, which decides
   * [defaultMode]. Returns whether git and the host managed it.
   */
  suspend fun refreshChanges(chosen: GitDiffMode?, ignoreWhitespace: Boolean): Boolean = changesLock.withLock {
    val request = ChangesRequest(chosen ?: defaultMode ?: GitDiffMode.Uncommitted, ignoreWhitespace)
    reloading = true
    try {
      val reply = fetchChanges(request) ?: return@withLock false
      if (chosen != null || defaultMode != null || reply !is GitReply.Loaded) return@withLock show(request, reply)
      val mode = defaultDiffMode(reply.value)
      defaultMode = mode
      if (mode == request.mode) return@withLock show(request, reply)
      val defaultRequest = request.copy(mode = mode)
      show(defaultRequest, fetchChanges(defaultRequest) ?: return@withLock false)
    } finally {
      reloading = false
    }
  }

  /** Loads the commits ahead of the comparison base; returns whether git and the host managed it. */
  suspend fun refreshCommits(): Boolean = commitsLock.withLock {
    val known = (commits as? CommitsContent.Loaded)?.commits?.fingerprint
    commitsReloading = true
    val reply = try {
      git.commits(root, known)
    } catch (_: IOException) {
      null
    } catch (_: HostRejected) {
      null
    } finally {
      commitsReloading = false
    }
    when (reply) {
      null -> if (commits == CommitsContent.Loading) commits = CommitsContent.Failed
      GitReply.Unchanged -> Unit
      is GitReply.Loaded -> commits = CommitsContent.Loaded(reply.value)
      is GitReply.Failed -> commits = CommitsContent.Failed
    }
    reply != null && reply !is GitReply.Failed
  }

  /** [request]'s reply, or null when the host couldn't run it, which fails a first load and keeps a shown one. */
  private suspend fun fetchChanges(request: ChangesRequest): GitReply<GitChanges>? {
    val known = (changes as? ChangesContent.Loaded)?.takeIf { it.request == request }?.changes?.fingerprint
    return try {
      git.changes(root, request.mode, request.ignoreWhitespace, known)
    } catch (error: IOException) {
      failFirstLoad(error.message.orEmpty())
    } catch (error: HostRejected) {
      failFirstLoad(error.error)
    }
  }

  private fun failFirstLoad(message: String): Nothing? {
    if (changes == ChangesContent.Loading) changes = ChangesContent.Failed(message)
    return null
  }

  private suspend fun show(request: ChangesRequest, reply: GitReply<GitChanges>): Boolean {
    when (reply) {
      GitReply.Unchanged -> Unit

      is GitReply.Failed -> changes = ChangesContent.Failed(reply.message)

      is GitReply.Loaded -> {
        val previous = (changes as? ChangesContent.Loaded)?.document
        val document = withContext(Dispatchers.Default) { DiffDocument.build(reply.value.diff, previous) }
        changes = ChangesContent.Loaded(request, reply.value, document)
      }
    }
    return reply !is GitReply.Failed
  }
}

/** Each workspace's [GitWorkspace], by [io.jeph.pipkin.data.folderKey], made the first time it's asked for. */
internal class GitWorkspaces(private val git: GitHost) {
  private val workspaces = HashMap<String, GitWorkspace>()

  operator fun get(root: String): GitWorkspace = workspaces.getOrPut(root) { GitWorkspace(root, git) }
}
