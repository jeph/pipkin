package io.jeph.pipkin.data

import android.content.Context
import androidx.core.content.edit
import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.IdleStop
import io.jeph.pipkin.host.GitDiffMode
import io.jeph.pipkin.ui.files.ExplorerSort
import io.jeph.pipkin.ui.files.SortKey
import io.jeph.pipkin.ui.git.ExplorerTab
import io.jeph.pipkin.ui.theme.AppTheme
import io.jeph.pipkin.workspace.SavedLayout
import io.jeph.pipkin.workspace.savedLayoutOf
import io.jeph.pipkin.workspace.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** App settings in the `pipkin` SharedPreferences file. Setters persist and publish immediately. */
@Suppress("TooManyFunctions") // One setter per setting, beside the readers they share.
internal class Preferences(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
  private val setupCompleteState = MutableStateFlow(prefs.getBoolean(SETUP_COMPLETE, false))
  private val setupCommandCopiedForState = MutableStateFlow(prefs.getString(SETUP_COMMAND_COPIED_FOR, null))
  private val setupPagesPassedState = MutableStateFlow(readStringSet(SETUP_PAGES_PASSED))
  private val themeState = MutableStateFlow(readTheme())
  private val lastProjectCwdState = MutableStateFlow(prefs.getString(LAST_PROJECT_CWD, null))
  private val workspacesState = MutableStateFlow(readWorkspaces())
  private val archivedState = MutableStateFlow(readLongMap(ARCHIVED_SESSIONS))
  private val touchedState = MutableStateFlow(readLongMap(TOUCHED_SESSIONS))
  private val autoArchiveState = MutableStateFlow(prefs.getBoolean(AUTO_ARCHIVE, false))
  private val autoArchiveDaysState = MutableStateFlow(
    prefs.getInt(AUTO_ARCHIVE_DAYS, AutoArchive.DEFAULT_DAYS).coerceIn(AutoArchive.DAYS),
  )
  private val showHiddenFilesState = MutableStateFlow(prefs.getBoolean(SHOW_HIDDEN_FILES, true))
  private val explorerSortState = MutableStateFlow(readExplorerSort())
  private val explorerTabsState = MutableStateFlow(readEnumMap(EXPLORER_TABS, ExplorerTab.entries))
  private val diffModesState = MutableStateFlow(readEnumMap(DIFF_MODES, GitDiffMode.entries))
  private val trustedRepositoriesState = MutableStateFlow(readStringSet(TRUSTED_REPOSITORIES))
  private val diffWrapLinesState = MutableStateFlow(prefs.getBoolean(DIFF_WRAP_LINES, false))
  private val diffHideWhitespaceState = MutableStateFlow(prefs.getBoolean(DIFF_HIDE_WHITESPACE, false))
  private val commitsCollapsedState = MutableStateFlow(prefs.getBoolean(COMMITS_COLLAPSED, true))
  private val maxRunningChatsState = MutableStateFlow(
    prefs.getInt(MAX_RUNNING_CHATS, AgentRegistry.DEFAULT_MAX_RUNNING).coerceIn(AgentRegistry.RUNNING_LIMITS),
  )
  private val idleStopState = prefs.getString(IDLE_STOP, null).let { name ->
    MutableStateFlow(IdleStop.entries.firstOrNull { it.name == name } ?: IdleStop.Default)
  }
  private val disabledExtensionsState = MutableStateFlow(readStringSet(DISABLED_EXTENSIONS))
  private val inAppNotificationsState = MutableStateFlow(prefs.getBoolean(IN_APP_NOTIFICATIONS, true))

  /** Onboarding finished. */
  val setupComplete: StateFlow<Boolean> = setupCompleteState.asStateFlow()

  /**
   * The Termux installation (`TermuxInstall.installation`) the setup command was last copied for, so that one may have
   * run it. A reinstall wiped what it set up, so a copy for another installation doesn't count.
   */
  val setupCommandCopiedFor: StateFlow<String?> = setupCommandCopiedForState.asStateFlow()

  /** Onboarding pages the user moved past without finishing them: the welcome page and skipped optional steps. */
  val setupPagesPassed: StateFlow<Set<String>> = setupPagesPassedState.asStateFlow()

  val theme: StateFlow<AppTheme> = themeState.asStateFlow()

  /** The workspace folder last opened, or last started a chat in, from the sidebar. */
  val lastProjectCwd: StateFlow<String?> = lastProjectCwdState.asStateFlow()

  /** Folders added as sidebar workspaces, newest first, kept even while they have no chats. */
  val workspaces: StateFlow<List<String>> = workspacesState.asStateFlow()

  /** Archived session files by path, each with when it was archived (epoch milliseconds). */
  val archivedSessions: StateFlow<Map<String, Long>> = archivedState.asStateFlow()

  /**
   * Session files that are not archived by path, each with when Pipkin last showed it, restored it, or saw its run end
   * (epoch milliseconds), which keeps it from auto archive as a new message would.
   */
  val touchedSessions: StateFlow<Map<String, Long>> = touchedState.asStateFlow()

  /** Whether chats nobody touched for [autoArchiveDays] are archived on their own; off until turned on. */
  val autoArchive: StateFlow<Boolean> = autoArchiveState.asStateFlow()

  /** How many days without a touch auto archive waits, within [AutoArchive.DAYS]. */
  val autoArchiveDays: StateFlow<Int> = autoArchiveDaysState.asStateFlow()

  /** Whether the file explorer lists dot files and folders; Paseo shows them unless asked not to. */
  val showHiddenFiles: StateFlow<Boolean> = showHiddenFilesState.asStateFlow()

  /** How the file explorer orders each folder; names A to Z until changed, as in Paseo. */
  val explorerSort: StateFlow<ExplorerSort> = explorerSortState.asStateFlow()

  /** The Explorer tab last chosen in each workspace, by [folderKey]; a workspace without one shows Changes. */
  val explorerTabs: StateFlow<Map<String, ExplorerTab>> = explorerTabsState.asStateFlow()

  /** The comparison last chosen in each workspace's Changes tab, by [folderKey]; only a user's choice is kept. */
  val diffModes: StateFlow<Map<String, GitDiffMode>> = diffModesState.asStateFlow()

  /** Workspaces, by [folderKey], whose repository the user trusted git to run in. */
  val trustedRepositories: StateFlow<Set<String>> = trustedRepositoriesState.asStateFlow()

  /** Whether diffs wrap long lines rather than scroll sideways; Paseo scrolls them until asked to wrap. */
  val diffWrapLines: StateFlow<Boolean> = diffWrapLinesState.asStateFlow()

  /** Whether diffs leave out whitespace-only changes (git's `-w`). */
  val diffHideWhitespace: StateFlow<Boolean> = diffHideWhitespaceState.asStateFlow()

  /** Whether the Changes tab's Commits section is folded; Paseo starts it folded. */
  val commitsCollapsed: StateFlow<Boolean> = commitsCollapsedState.asStateFlow()

  /** How many chats may have Pi running at once, within [AgentRegistry.RUNNING_LIMITS]. */
  val maxRunningChats: StateFlow<Int> = maxRunningChatsState.asStateFlow()

  /** Whether chats that finish or wait on a dialog off screen show in the pill under the header bar. */
  val inAppNotifications: StateFlow<Boolean> = inAppNotificationsState.asStateFlow()

  /** How long a chat that is neither working nor shown keeps its Pi. */
  val idleStop: StateFlow<IdleStop> = idleStopState.asStateFlow()

  /** The extensions Pipkin bundles that the user turned off, by [BundledExtension.id]; a chat's next Pi leaves them out. */
  val disabledExtensions: StateFlow<Set<String>> = disabledExtensionsState.asStateFlow()

  fun setSetupComplete(complete: Boolean) {
    prefs.edit { putBoolean(SETUP_COMPLETE, complete) }
    setupCompleteState.value = complete
  }

  fun setSetupCommandCopiedFor(installation: String) {
    if (setupCommandCopiedForState.value == installation) return
    prefs.edit { putString(SETUP_COMMAND_COPIED_FOR, installation) }
    setupCommandCopiedForState.value = installation
  }

  fun passSetupPage(page: String) {
    val updated = setupPagesPassedState.value + page
    if (updated.size == setupPagesPassedState.value.size) return
    prefs.edit { putString(SETUP_PAGES_PASSED, JSONArray(updated).toString()) }
    setupPagesPassedState.value = updated
  }

  fun setTheme(theme: AppTheme) {
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

  /**
   * Takes [cwd] off the sidebar's workspaces; it shows again only while it has chats or once added back. Its
   * repository is no longer trusted, so adding it back asks again.
   */
  fun removeWorkspace(cwd: String) {
    val updated = workspacesState.value.filterNot { sameFolder(it, cwd) }
    val trusted = trustedRepositoriesState.value - folderKey(cwd)
    prefs.edit {
      putString(WORKSPACES, JSONArray(updated).toString())
      putString(TRUSTED_REPOSITORIES, JSONArray(trusted).toString())
    }
    workspacesState.value = updated
    trustedRepositoriesState.value = trusted
  }

  /** Marks [paths] archived at [at]; a path already archived keeps its time. Their touches go with them. */
  fun archiveSessions(paths: Collection<String>, at: Long) {
    writeArchived(
      archived = archivedState.value + paths.filterNot(archivedState.value::containsKey).associateWith { at },
      touched = touchedState.value - paths.toSet(),
    )
  }

  fun unarchiveSessions(paths: Collection<String>) {
    writeArchived(archived = archivedState.value - paths.toSet(), touched = touchedState.value)
  }

  /** Records that [paths] were touched at [at]. */
  fun touchSessions(paths: Collection<String>, at: Long) {
    if (paths.isEmpty()) return
    val updated = touchedState.value + paths.associateWith { at }
    prefs.edit { putString(TOUCHED_SESSIONS, JSONObject(updated).toString()) }
    touchedState.value = updated
  }

  fun setAutoArchive(enabled: Boolean) {
    prefs.edit { putBoolean(AUTO_ARCHIVE, enabled) }
    autoArchiveState.value = enabled
  }

  /** Sets [autoArchiveDays], kept within [AutoArchive.DAYS]. */
  fun setAutoArchiveDays(days: Int) {
    val allowed = days.coerceIn(AutoArchive.DAYS)
    prefs.edit { putInt(AUTO_ARCHIVE_DAYS, allowed) }
    autoArchiveDaysState.value = allowed
  }

  fun setShowHiddenFiles(show: Boolean) {
    prefs.edit { putBoolean(SHOW_HIDDEN_FILES, show) }
    showHiddenFilesState.value = show
  }

  fun setExplorerSort(sort: ExplorerSort) {
    prefs.edit {
      putString(EXPLORER_SORT_KEY, sort.key.name)
      putBoolean(EXPLORER_SORT_DESCENDING, sort.descending)
    }
    explorerSortState.value = sort
  }

  fun setExplorerTab(cwd: String, tab: ExplorerTab) {
    val updated = explorerTabsState.value + (folderKey(cwd) to tab)
    prefs.edit { putString(EXPLORER_TABS, JSONObject(updated.mapValues { it.value.name }).toString()) }
    explorerTabsState.value = updated
  }

  fun setDiffMode(cwd: String, mode: GitDiffMode) {
    val updated = diffModesState.value + (folderKey(cwd) to mode)
    prefs.edit { putString(DIFF_MODES, JSONObject(updated.mapValues { it.value.name }).toString()) }
    diffModesState.value = updated
  }

  fun trustRepository(cwd: String) {
    val updated = trustedRepositoriesState.value + folderKey(cwd)
    prefs.edit { putString(TRUSTED_REPOSITORIES, JSONArray(updated).toString()) }
    trustedRepositoriesState.value = updated
  }

  fun setDiffWrapLines(wrap: Boolean) {
    prefs.edit { putBoolean(DIFF_WRAP_LINES, wrap) }
    diffWrapLinesState.value = wrap
  }

  fun setDiffHideWhitespace(hide: Boolean) {
    prefs.edit { putBoolean(DIFF_HIDE_WHITESPACE, hide) }
    diffHideWhitespaceState.value = hide
  }

  fun setCommitsCollapsed(collapsed: Boolean) {
    prefs.edit { putBoolean(COMMITS_COLLAPSED, collapsed) }
    commitsCollapsedState.value = collapsed
  }

  /** Sets [maxRunningChats], kept within [AgentRegistry.RUNNING_LIMITS]. */
  fun setMaxRunningChats(limit: Int) {
    val allowed = limit.coerceIn(AgentRegistry.RUNNING_LIMITS)
    prefs.edit { putInt(MAX_RUNNING_CHATS, allowed) }
    maxRunningChatsState.value = allowed
  }

  fun setInAppNotifications(enabled: Boolean) {
    prefs.edit { putBoolean(IN_APP_NOTIFICATIONS, enabled) }
    inAppNotificationsState.value = enabled
  }

  fun setIdleStop(stop: IdleStop) {
    prefs.edit { putString(IDLE_STOP, stop.name) }
    idleStopState.value = stop
  }

  fun setExtensionEnabled(extension: BundledExtension, enabled: Boolean) {
    val updated = if (enabled) {
      disabledExtensionsState.value - extension.id
    } else {
      disabledExtensionsState.value +
        extension.id
    }
    prefs.edit { putString(DISABLED_EXTENSIONS, JSONArray(updated).toString()) }
    disabledExtensionsState.value = updated
  }

  /** The tabs and panes last shown, for a restarted Pipkin to show again; null before any were saved. */
  fun savedLayout(): SavedLayout? = prefs.getString(LAYOUT, null)?.let(::savedLayoutOf)

  fun setSavedLayout(layout: SavedLayout) {
    prefs.edit { putString(LAYOUT, layout.toJson()) }
  }

  /** The saved theme. Before there were palettes to pick, the setting was Light or Dark, which kept their colours. */
  private fun readTheme(): AppTheme = when (val name = prefs.getString(THEME, null)) {
    "Light" -> AppTheme.Porcelain
    "Dark" -> AppTheme.Cobalt
    else -> AppTheme.entries.firstOrNull { it.name == name } ?: AppTheme.System
  }

  private fun readWorkspaces(): List<String> {
    val array = JSONArray(prefs.getString(WORKSPACES, null) ?: return emptyList())
    return List(array.length()) { array.getString(it) }
  }

  private fun readExplorerSort(): ExplorerSort {
    val key = SortKey.entries.firstOrNull { it.name == prefs.getString(EXPLORER_SORT_KEY, null) }
      ?: return ExplorerSort.Default
    return ExplorerSort(key, prefs.getBoolean(EXPLORER_SORT_DESCENDING, key.descendingFirst))
  }

  /** The JSON array of strings at [key], as a set. */
  private fun readStringSet(key: String): Set<String> {
    val array = JSONArray(prefs.getString(key, null) ?: return emptySet())
    return List(array.length()) { array.getString(it) }.toSet()
  }

  /** The JSON object at [key] of folder keys to names of [entries]; names no entry has any more are dropped. */
  private fun <T : Enum<T>> readEnumMap(key: String, entries: List<T>): Map<String, T> {
    val json = JSONObject(prefs.getString(key, null) ?: return emptyMap())
    return json.keys().asSequence()
      .mapNotNull { folder -> entries.firstOrNull { it.name == json.optString(folder) }?.let { folder to it } }
      .toMap()
  }

  private fun writeArchived(archived: Map<String, Long>, touched: Map<String, Long>) {
    prefs.edit {
      putString(ARCHIVED_SESSIONS, JSONObject(archived).toString())
      putString(TOUCHED_SESSIONS, JSONObject(touched).toString())
    }
    archivedState.value = archived
    touchedState.value = touched
  }

  /** The JSON object at [key] of strings to epoch milliseconds. */
  private fun readLongMap(key: String): Map<String, Long> {
    val json = JSONObject(prefs.getString(key, null) ?: return emptyMap())
    return json.keys().asSequence().associateWith(json::getLong)
  }

  private companion object {
    const val FILE = "pipkin"
    const val SETUP_COMPLETE = "setupComplete"
    const val SETUP_COMMAND_COPIED_FOR = "setupCommandCopiedFor"
    const val SETUP_PAGES_PASSED = "setupPagesPassed"
    const val THEME = "theme"
    const val LAST_PROJECT_CWD = "lastProjectCwd"
    const val WORKSPACES = "workspaces"
    const val ARCHIVED_SESSIONS = "archivedSessions"
    const val TOUCHED_SESSIONS = "touchedSessions"
    const val AUTO_ARCHIVE = "autoArchive"
    const val AUTO_ARCHIVE_DAYS = "autoArchiveDays"
    const val SHOW_HIDDEN_FILES = "showHiddenFiles"
    const val EXPLORER_SORT_KEY = "explorerSortKey"
    const val EXPLORER_SORT_DESCENDING = "explorerSortDescending"
    const val EXPLORER_TABS = "explorerTabs"
    const val DIFF_MODES = "diffModes"
    const val TRUSTED_REPOSITORIES = "trustedRepositories"
    const val DIFF_WRAP_LINES = "diffWrapLines"
    const val DIFF_HIDE_WHITESPACE = "diffHideWhitespace"
    const val COMMITS_COLLAPSED = "commitsCollapsed"
    const val MAX_RUNNING_CHATS = "maxRunningChats"
    const val IDLE_STOP = "idleStop"
    const val LAYOUT = "layout"
    const val DISABLED_EXTENSIONS = "disabledExtensions"
    const val IN_APP_NOTIFICATIONS = "inAppNotifications"
  }
}
