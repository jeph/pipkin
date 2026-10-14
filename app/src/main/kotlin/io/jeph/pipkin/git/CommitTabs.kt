package io.jeph.pipkin.git

import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.GitCommit
import io.jeph.pipkin.host.GitErrorCode
import io.jeph.pipkin.host.GitHost
import io.jeph.pipkin.host.GitReply
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.ui.git.DiffDocument
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.ShownTabs
import io.jeph.pipkin.workspace.WorkspaceTab
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface CommitDiffState {
  data object Loading : CommitDiffState

  /** The diff as git gave it with whitespace changes left out or not, as [ignoreWhitespace] says. */
  class Loaded(val ignoreWhitespace: Boolean, val document: DiffDocument) : CommitDiffState

  /** Git can't find the commit any more. */
  data object NotFound : CommitDiffState

  class Failed(val message: String) : CommitDiffState
}

/** One open commit of the workspace folder [cwd]: what it changed, read once, since a commit never changes. */
internal class CommitTab(val id: Int, val cwd: String, val commit: GitCommit, private val git: GitHost) {
  private val stateFlow = MutableStateFlow<CommitDiffState>(CommitDiffState.Loading)

  /** Paseo's commit tab title: the short SHA, then the subject. */
  val title: String get() = "${commit.shortSha} ${commit.subject}"

  val state: StateFlow<CommitDiffState> = stateFlow.asStateFlow()

  /**
   * Reads the diff unless it's already loaded with whitespace changes [ignoreWhitespace]d or not; what was read
   * before stays shown until then. A commit that was amended or rebased away still shows while git keeps it.
   */
  suspend fun load(ignoreWhitespace: Boolean) {
    val shown = stateFlow.value
    if (shown is CommitDiffState.Loaded && shown.ignoreWhitespace == ignoreWhitespace) return
    val reply = try {
      git.commitDiff(cwd, commit.sha, ignoreWhitespace)
    } catch (error: HostRejected) {
      stateFlow.value = CommitDiffState.Failed(error.error)
      return
    } catch (error: IOException) {
      stateFlow.value = CommitDiffState.Failed(error.message.orEmpty())
      return
    }
    stateFlow.value = when (reply) {
      // Only a reply carrying the fingerprint sent as known is unchanged, and none is sent.
      GitReply.Unchanged -> shown

      is GitReply.Failed -> if (reply.code == GitErrorCode.CommitNotFound) {
        CommitDiffState.NotFound
      } else {
        CommitDiffState.Failed(reply.message)
      }

      is GitReply.Loaded -> {
        val previous = (shown as? CommitDiffState.Loaded)?.document
        val document = withContext(Dispatchers.Default) { DiffDocument.build(reply.value, previous) }
        CommitDiffState.Loaded(ignoreWhitespace, document)
      }
    }
  }
}

/**
 * The open commits, which belong to a folder rather than a chat, like files: every chat in a folder shows the same
 * ones. A commit has at most one tab per folder. Tabs live in memory only, so they're gone after a restart. A folder
 * that stops being one of the [trusted] ones, as removing its workspace makes it, loses its tabs, so none of them
 * runs git there again. Main-thread confined.
 */
internal class CommitTabRegistry(
  private val git: GitHost,
  private val shownTabs: ShownTabs,
  trusted: StateFlow<Set<String>>,
  scope: CoroutineScope,
) {
  private val tabsFlow = MutableStateFlow<List<CommitTab>>(emptyList())
  private var nextId = 1

  init {
    scope.launch {
      trusted.collect { folders -> tabsFlow.value.filterNot { it.cwd in folders }.forEach(::close) }
    }
  }

  /** Oldest first. */
  val tabs: StateFlow<List<CommitTab>> = tabsFlow.asStateFlow()

  /**
   * Shows [commit]'s tab in [chat]'s folder in place of the chat in [pane], opening one when the commit has none.
   * Returns false, showing nothing, when the other pane shows the commit.
   */
  fun open(pane: Pane, chat: AgentProcess, commit: GitCommit): Boolean {
    val folder = folderKey(chat.cwd)
    val tab = tabsFlow.value.firstOrNull { it.cwd == folder && it.commit.sha == commit.sha }
      ?: CommitTab(nextId++, folder, commit, git).also { opened -> tabsFlow.update { it + opened } }
    return shownTabs.show(pane, chat, WorkspaceTab.Commit(tab))
  }

  fun close(tab: CommitTab) {
    tabsFlow.update { it - tab }
    shownTabs.forget(WorkspaceTab.Commit(tab))
  }
}
