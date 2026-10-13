package io.jeph.pipkin.data

import io.jeph.pipkin.host.SessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** An archived chat and when it was archived (epoch milliseconds). */
internal data class ArchivedSession(val session: SessionInfo, val archivedAt: Long)

/**
 * Which chats are archived. An archived chat leaves its workspace's tab menu and opens only as a read-only transcript
 * until it is restored; its session file stays until it is deleted from the archive, the only place chats are deleted.
 * Archive marks live in [Preferences] by session path, so Pi and its files know nothing of them.
 */
internal class SessionArchive(
  private val repository: SessionRepository,
  private val preferences: Preferences,
  scope: CoroutineScope,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  /** Sessions that are not archived, newest first: the chats of the workspaces' tab menus. */
  val active: StateFlow<List<SessionInfo>> =
    combine(repository.sessions, preferences.archivedSessions, ::activeOf)
      .stateIn(scope, SharingStarted.Eagerly, activeOf(repository.sessions.value, preferences.archivedSessions.value))

  /** Archived sessions whose files still exist, most recently archived first. */
  val archived: StateFlow<List<ArchivedSession>> =
    combine(repository.sessions, preferences.archivedSessions, ::archivedOf)
      .stateIn(scope, SharingStarted.Eagerly, archivedOf(repository.sessions.value, preferences.archivedSessions.value))

  fun isArchived(path: String): Boolean = path in preferences.archivedSessions.value

  /**
   * Archives the session files [paths]. Their workspaces [cwds] stay in the sidebar, even once they have no chats
   * left, until they are removed.
   */
  fun archive(paths: Collection<String>, cwds: Collection<String>) {
    cwds.forEach(preferences::addWorkspace)
    preferences.archiveSessions(paths, clock())
  }

  /** Takes [paths] out of the archive again, as Undo does right after archiving them. */
  fun unarchive(paths: Collection<String>) {
    preferences.unarchiveSessions(paths)
  }

  /**
   * Restores those of [sessions] whose workspace folder still exists, adding a workspace that was removed back to
   * the sidebar. Returns the restored sessions.
   */
  fun restore(sessions: Collection<SessionInfo>): List<SessionInfo> {
    val restorable = sessions.filter { it.cwdExists }
    restorable.forEach { preferences.addWorkspace(it.cwd) }
    preferences.unarchiveSessions(restorable.map { it.path })
    return restorable
  }

  /**
   * Removes the workspace [cwd] from the sidebar and archives its chats [paths]. Adding the folder back later shows
   * it empty: its chats stay archived.
   */
  fun removeWorkspace(cwd: String, paths: Collection<String>) {
    preferences.archiveSessions(paths, clock())
    preferences.removeWorkspace(cwd)
    if (preferences.lastProjectCwd.value?.let { sameFolder(it, cwd) } == true) preferences.setLastProjectCwd(null)
  }

  /** Deletes archived session files; throws like [SessionRepository.delete]. */
  suspend fun delete(paths: Collection<String>) {
    repository.delete(paths)
    preferences.unarchiveSessions(paths)
  }

  private companion object {
    fun activeOf(sessions: List<SessionInfo>, archived: Map<String, Long>): List<SessionInfo> =
      sessions.filterNot { it.path in archived }

    fun archivedOf(sessions: List<SessionInfo>, archived: Map<String, Long>): List<ArchivedSession> = sessions
      .mapNotNull { session -> archived[session.path]?.let { ArchivedSession(session, it) } }
      .sortedByDescending { it.archivedAt }
  }
}
