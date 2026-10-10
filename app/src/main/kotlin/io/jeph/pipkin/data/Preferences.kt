package io.jeph.pipkin.data

import android.content.Context
import androidx.core.content.edit
import io.jeph.pipkin.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** App settings in the `pipkin` SharedPreferences file. Setters persist and publish immediately. */
internal class Preferences(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
  private val setupCompleteState = MutableStateFlow(prefs.getBoolean(SETUP_COMPLETE, false))
  private val themeState = prefs.getString(THEME, null).let { name ->
    MutableStateFlow(ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.System)
  }
  private val lastProjectCwdState = MutableStateFlow(prefs.getString(LAST_PROJECT_CWD, null))
  private val workspacesState = MutableStateFlow(readWorkspaces())
  private val collapsedState = MutableStateFlow(prefs.getStringSet(COLLAPSED_WORKSPACES, null).orEmpty().toSet())

  /** Onboarding finished or was skipped. */
  val setupComplete: StateFlow<Boolean> = setupCompleteState.asStateFlow()

  val theme: StateFlow<ThemeMode> = themeState.asStateFlow()

  /** The workspace folder of the last chat started from the sidebar. */
  val lastProjectCwd: StateFlow<String?> = lastProjectCwdState.asStateFlow()

  /** Folders added as sidebar workspaces, newest first, kept even while they have no chats. */
  val workspaces: StateFlow<List<String>> = workspacesState.asStateFlow()

  /** Workspaces whose chats the sidebar hides. */
  val collapsedWorkspaces: StateFlow<Set<String>> = collapsedState.asStateFlow()

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

  fun setWorkspaceCollapsed(cwd: String, collapsed: Boolean) {
    val updated = if (collapsed) collapsedState.value + cwd else collapsedState.value - cwd
    prefs.edit { putStringSet(COLLAPSED_WORKSPACES, updated) }
    collapsedState.value = updated
  }

  private fun readWorkspaces(): List<String> {
    val array = JSONArray(prefs.getString(WORKSPACES, null) ?: return emptyList())
    return List(array.length()) { array.getString(it) }
  }

  private companion object {
    const val FILE = "pipkin"
    const val SETUP_COMPLETE = "setupComplete"
    const val THEME = "theme"
    const val LAST_PROJECT_CWD = "lastProjectCwd"
    const val WORKSPACES = "workspaces"
    const val COLLAPSED_WORKSPACES = "collapsedWorkspaces"
  }
}
