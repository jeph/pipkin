package io.jeph.pipkin.data

import io.jeph.pipkin.host.SessionInfo

/** [cwd] without a trailing slash, so `~/app` and `~/app/` are one workspace. */
internal fun folderKey(cwd: String): String = cwd.trimEnd('/').ifEmpty { "/" }

internal fun sameFolder(a: String, b: String): Boolean = folderKey(a) == folderKey(b)

/** A sidebar workspace: a folder and its chats, newest first. */
internal data class Workspace(val cwd: String, val chats: List<SessionInfo>)

/**
 * The sidebar's workspaces: [added] folders without chats first (newest added first, so a folder
 * just added shows at the top), then every folder with chats, ordered by its newest chat.
 * [sessions] arrive newest first.
 */
internal fun workspaces(sessions: List<SessionInfo>, added: List<String>): List<Workspace> {
  val withChats = sessions.groupBy { folderKey(it.cwd) }
  val empty = added.map(::folderKey).distinct().filter { it !in withChats }
  return empty.map { Workspace(it, emptyList()) } + withChats.map { (cwd, chats) -> Workspace(cwd, chats) }
}
