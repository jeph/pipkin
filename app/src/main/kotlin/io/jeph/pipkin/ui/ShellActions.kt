package io.jeph.pipkin.ui

import android.annotation.SuppressLint
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.ChatKey
import io.jeph.pipkin.agent.RpcError
import io.jeph.pipkin.agent.TooManyRunning
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.data.sameFolder
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.SessionInfo
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A one-shot message for the shell's snackbar. */
internal sealed interface ShellNotice {
  /** A failure; a null [message] has no message of its own. */
  data class Failure(val message: String?) : ShellNotice

  /** Chats were archived from the tab menu or the chat header; Undo takes [paths] back out of the archive. */
  data class Archived(val paths: List<String>) : ShellNotice
}

/**
 * The shell's chat operations: which chat the chat panel shows, workspaces and new chats, the tab menu's rename, and
 * archiving, restoring and deleting chats. Notices surface as [notices] for the shell's snackbar. Main thread only.
 */
internal class ShellActions(private val graph: AppGraph) {
  private val noticeChannel = Channel<ShellNotice>(Channel.BUFFERED)
  private val registry get() = graph.registry
  private val sessions get() = graph.sessionRepository
  private val sessionArchive get() = graph.sessionArchive

  /** Session files of the chat last shown in each workspace, by folder key, so returning to one shows that chat. */
  private val lastShownIn = HashMap<String, String>()

  val notices: Flow<ShellNotice> = noticeChannel.receiveAsFlow()

  /** Termux's home from the last host hello; upstream Termux always uses [TERMUX_HOME] before any host ran. */
  val home: String get() = graph.hostClient.lastHello.value?.home?.takeIf { it.isNotEmpty() } ?: TERMUX_HOME

  /** Shows [session]'s chat; false (with a notice) when four chats are working. */
  fun openSession(session: SessionInfo): Boolean = openChat(session.cwd, session.path)

  /** Shows the chat in session file [sessionPath]; false (with a notice) when four chats are working. */
  fun openChat(cwd: String, sessionPath: String): Boolean = show { registry.open(cwd, sessionPath) }

  /** Shows a new chat in [cwd]; false (with a notice) when four chats are working. */
  fun newChat(cwd: String): Boolean = show { registry.open(cwd, null) }

  /**
   * Shows the workspace [cwd]: the chat last shown there while it is still open or listed, else its newest chat, else
   * a new one; false (with a notice) when four chats are working.
   */
  fun openWorkspace(cwd: String): Boolean = show { workspaceChat(cwd) }

  /** The process for [openWorkspace]; throws [TooManyRunning] when it needs a slot and all four chats are working. */
  private fun workspaceChat(cwd: String): AgentProcess {
    registry.visible.value?.takeIf { sameFolder(it.cwd, cwd) }?.let { return it }
    val last = lastShownIn[folderKey(cwd)]?.takeIf { path ->
      !sessionArchive.isArchived(path) &&
        (ChatKey.session(path) in registry.processes.value || sessionArchive.active.value.any { it.path == path })
    }
    return registry.open(cwd, last ?: newestIn(cwd)?.path)
  }

  /**
   * Shows the most recent chat that is not archived, in [cwd] when given, or a new one there; without [cwd], in the
   * last project (or home) when there is none.
   */
  private fun openMostRecent(cwd: String? = null) {
    show { mostRecent(cwd) }
  }

  /** The process for [openMostRecent]; throws [TooManyRunning] when it needs a slot and all four chats are working. */
  private fun mostRecent(cwd: String? = null): AgentProcess {
    val newest = newestIn(cwd) ?: return registry.open(cwd ?: graph.preferences.lastProjectCwd.value ?: home, null)
    return registry.open(newest.cwd, newest.path)
  }

  /** The newest chat that is not archived, in [cwd] when given. */
  private fun newestIn(cwd: String?): SessionInfo? =
    // The archive marks are the source of truth; the active list catches up with them a moment later.
    sessionArchive.active.value.firstOrNull {
      !sessionArchive.isArchived(it.path) && (cwd == null || sameFolder(it.cwd, cwd))
    }

  /** On entering the main screen without a chat: waits for the session list, then [openMostRecent]. */
  suspend fun openAtStartup() {
    if (registry.visible.value != null) return
    if (!sessions.loaded.value) sessions.refresh()
    sessions.loaded.first { it }
    if (registry.visible.value == null) openMostRecent()
  }

  /** Renames [session] through its Pi process, starting one in the background if it is not open. */
  fun rename(session: SessionInfo, name: String) {
    graph.scope.launch {
      val process = try {
        registry.open(session.cwd, session.path)
      } catch (error: TooManyRunning) {
        notify(ShellNotice.Failure(error.message))
        return@launch
      }
      val state = withTimeoutOrNull(START_TIMEOUT_MILLIS) {
        process.state.first { it !is AgentProcessState.Starting && it !is AgentProcessState.Installing }
      }
      if (state !is AgentProcessState.Ready) {
        notify(ShellNotice.Failure((state as? AgentProcessState.Failed)?.message))
        return@launch
      }
      try {
        process.setSessionName(name)
      } catch (error: RpcError) {
        notify(ShellNotice.Failure(error.error))
      }
    }
  }

  /** Whether the chat in session file [path] has a Pi run going, which archiving it would stop. */
  fun isWorking(path: String): Boolean = processFor(path)?.busy?.value == true

  /** How many chats in the workspace [cwd] have a Pi run going. */
  fun workingIn(cwd: String): Int = processesIn(cwd).count { it.busy.value }

  /** Archives [session], stopping its run if one is going, and offers Undo. */
  fun archive(session: SessionInfo) {
    sessionArchive.archive(listOf(session.path), listOf(session.cwd))
    stop(listOfNotNull(processFor(session.path)), replaceIn = session.cwd)
    notify(ShellNotice.Archived(listOf(session.path)))
  }

  /** Archives the chat [process] shows, stopping its run if one is going, and offers Undo. */
  fun archive(process: AgentProcess) {
    val path = process.sessionFile.value ?: return
    sessionArchive.archive(listOf(path), listOf(process.cwd))
    stop(listOf(process), replaceIn = process.cwd)
    notify(ShellNotice.Archived(listOf(path)))
  }

  /** Undo of [archive]. */
  fun unarchive(paths: List<String>) = sessionArchive.unarchive(paths)

  /**
   * Removes the workspace [cwd] from the sidebar and archives every chat in it, including ones open in Pi that have
   * no saved history yet; their runs stop.
   */
  fun removeWorkspace(cwd: String) {
    val open = processesIn(cwd)
    val paths = sessionArchive.active.value.filter { sameFolder(it.cwd, cwd) }.map { it.path } +
      open.mapNotNull { it.sessionFile.value }
    sessionArchive.removeWorkspace(cwd, paths.distinct())
    stop(open, replaceIn = null)
  }

  /** Restores the archived [sessions] whose folder still exists; returns those restored. */
  fun restore(sessions: Collection<SessionInfo>): List<SessionInfo> = sessionArchive.restore(sessions)

  /** Deletes archived chats' session files; returns the user-facing failure, or null once they are gone. */
  suspend fun deleteArchived(paths: Collection<String>): String? = try {
    sessionArchive.delete(paths)
    null
  } catch (error: IOException) {
    error.message.orEmpty()
  } catch (error: HostRejected) {
    error.error
  }

  private fun processFor(path: String): AgentProcess? =
    registry.processes.value.values.firstOrNull { it.sessionFile.value == path }

  private fun processesIn(cwd: String): List<AgentProcess> =
    registry.processes.value.values.filter { sameFolder(it.cwd, cwd) }

  /**
   * Stops [processes]: a working run is aborted first so Pi saves what it has, then Pi ends. A shown chat among them
   * is replaced by the most recent one that is not archived, in the workspace [replaceIn] when given.
   */
  private fun stop(processes: List<AgentProcess>, replaceIn: String?) {
    if (processes.isEmpty()) return
    val shown = registry.visible.value
    val closing = processes.map { process ->
      graph.scope.launch {
        if (process.busy.value) {
          try {
            process.abort()
          } catch (_: RpcError) {
            // Pi stopped on its own; closing it below is all that is left.
          }
        }
        registry.close(process)
      }
    }
    if (processes.none { it === shown }) return
    try {
      registry.setVisible(mostRecent(replaceIn))
    } catch (_: TooManyRunning) {
      // The working chats being stopped still hold every slot; the replacement opens once they have closed, unless
      // another chat was opened meanwhile.
      registry.setVisible(null)
      graph.scope.launch {
        closing.joinAll()
        if (registry.visible.value == null) openMostRecent(replaceIn)
      }
    }
  }

  private inline fun show(open: () -> AgentProcess): Boolean {
    // Recorded before opening: making room for the next chat can close the shown one when it is the only idle chat.
    registry.visible.value?.let { shown -> shown.sessionFile.value?.let { lastShownIn[folderKey(shown.cwd)] = it } }
    return try {
      registry.setVisible(open())
      true
    } catch (error: TooManyRunning) {
      notify(ShellNotice.Failure(error.message))
      false
    }
  }

  private fun notify(notice: ShellNotice) {
    noticeChannel.trySend(notice)
  }

  private companion object {
    // Termux's own fixed home, not a path in Pipkin's storage.
    @SuppressLint("SdCardPath")
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val START_TIMEOUT_MILLIS = 60_000L
  }
}
