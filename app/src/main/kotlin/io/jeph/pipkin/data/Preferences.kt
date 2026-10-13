package io.jeph.pipkin.data

import android.content.Context
import androidx.core.content.edit
import io.jeph.pipkin.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** App settings in the `pipkin` SharedPreferences file. Setters persist and publish immediately. */
internal class Preferences(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
  private val setupCompleteState = MutableStateFlow(prefs.getBoolean(SETUP_COMPLETE, false))
  private val themeState = prefs.getString(THEME, null).let { name ->
    MutableStateFlow(ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.System)
  }
  private val lastProjectCwdState = MutableStateFlow(prefs.getString(LAST_PROJECT_CWD, null))
  private val workspacesState = MutableStateFlow(readWorkspaces())
  private val archivedState = MutableStateFlow(readArchived())
  private val showHiddenFilesState = MutableStateFlow(prefs.getBoolean(SHOW_HIDDEN_FILES, true))

  /** Onboarding finished or was skipped. */
  val setupComplete: StateFlow<Boolean> = setupCompleteState.asStateFlow()

  val theme: StateFlow<ThemeMode> = themeState.asStateFlow()

  /** The workspace folder last opened, or last started a chat in, from the sidebar. */
  val lastProjectCwd: StateFlow<String?> = lastProjectCwdState.asStateFlow()

  /** Folders added as sidebar workspaces, newest first, kept even while they have no chats. */
  val workspaces: StateFlow<List<String>> = workspacesState.asStateFlow()

  /** Archived session files by path, each with when it was archived (epoch milliseconds). */
  val archivedSessions: StateFlow<Map<String, Long>> = archivedState.asStateFlow()

  /** Whether the file explorer lists dot files and folders; Paseo shows them unless asked not to. */
  val showHiddenFiles: StateFlow<Boolean> = showHiddenFilesState.asStateFlow()

  fun setSetupComplete(complete: Boolean) {
    prefs.edit { putBoolean(SETUP_COMPLETE, complete) }
    setupCompleteState.value = complete
  }

  fun setTheme(theme: ThemeMode) {
    prefs.edit { putString(THEME, theme.name) }
    themeState.value = theme
  }

  fun setLastProjectCwd(cwd: String?) {
    prefs.edit { putString(LAST_PROJECT_CWD, cwd) }
    lastProjectCwdState.value = cwd
  }

  /** Adds [cwd] as the newest workspace; a folder that is already one is left where it is. */
  fun addWorkspace(cwd: String) {
    val current = workspacesState.value
    if (current.any { sameFolder(it, cwd) }) return
    val updated = listOf(folderKey(cwd)) + current
    prefs.edit { putString(WORKSPACES, JSONArray(updated).toString()) }
    workspacesState.value = updated
  }

  /** Takes [cwd] off the sidebar's workspaces; it shows again only while it has chats or once added back. */
  fun removeWorkspace(cwd: String) {
    val updated = workspacesState.value.filterNot { sameFolder(it, cwd) }
    prefs.edit { putString(WORKSPACES, JSONArray(updated).toString()) }
    workspacesState.value = updated
  }

  /** Marks [paths] archived at [at]; a path already archived keeps its time. */
  fun archiveSessions(paths: Collection<String>, at: Long) {
    writeArchived(archivedState.value + paths.filterNot(archivedState.value::containsKey).associateWith { at })
  }

  fun unarchiveSessions(paths: Collection<String>) {
    writeArchived(archivedState.value - paths.toSet())
  }

  fun setShowHiddenFiles(show: Boolean) {
    prefs.edit { putBoolean(SHOW_HIDDEN_FILES, show) }
    showHiddenFilesState.value = show
  }

  private fun readWorkspaces(): List<String> {
    val array = JSONArray(prefs.getString(WORKSPACES, null) ?: return emptyList())
    return List(array.length()) { array.getString(it) }
  }

  private fun writeArchived(archived: Map<String, Long>) {
    prefs.edit { putString(ARCHIVED_SESSIONS, JSONObject(archived).toString()) }
    archivedState.value = archived
  }

  private fun readArchived(): Map<String, Long> {
    val json = JSONObject(prefs.getString(ARCHIVED_SESSIONS, null) ?: return emptyMap())
    return json.keys().asSequence().associateWith(json::getLong)
  }

  private companion object {
    const val FILE = "pipkin"
    const val SETUP_COMPLETE = "setupComplete"
    const val THEME = "theme"
    const val LAST_PROJECT_CWD = "lastProjectCwd"
    const val WORKSPACES = "workspaces"
    const val ARCHIVED_SESSIONS = "archivedSessions"
    const val SHOW_HIDDEN_FILES = "showHiddenFiles"
  }
}
