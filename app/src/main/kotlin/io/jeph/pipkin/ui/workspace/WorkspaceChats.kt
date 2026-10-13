package io.jeph.pipkin.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.sameFolder
import io.jeph.pipkin.host.SessionInfo

/**
 * The tab menu's chats for the workspace of a shown chat: its saved chats, newest first, and whether the shown chat
 * has no saved history yet, which gives it a row of its own above them.
 */
internal class WorkspaceChats(val chats: List<SessionInfo>, val shownUnsaved: Boolean) {
  val count: Int get() = chats.size + if (shownUnsaved) 1 else 0
}

/** The [WorkspaceChats] of the workspace [process] works in, with [process] as the shown chat. */
@Composable
internal fun rememberWorkspaceChats(process: AgentProcess): WorkspaceChats {
  val sessions by LocalContext.current.appGraph.sessionArchive.active.collectAsStateWithLifecycle()
  val shownPath by process.sessionFile.collectAsStateWithLifecycle()
  return remember(sessions, shownPath, process.cwd) {
    val chats = sessions.filter { sameFolder(it.cwd, process.cwd) }
    WorkspaceChats(chats, shownUnsaved = chats.none { it.path == shownPath })
  }
}
