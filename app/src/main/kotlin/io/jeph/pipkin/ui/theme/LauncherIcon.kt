package io.jeph.pipkin.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.jeph.pipkin.MainActivity
import kotlinx.coroutines.flow.Flow

/**
 * Shows the picked theme's icon on the launcher by keeping its entry, one of the manifest's `Launcher` aliases, the
 * only one enabled. System and Porcelain share the default icon, so System keeps one icon as the phone goes in and
 * out of dark mode rather than swapping entries, which some launchers answer by dropping the icon from the home
 * screen.
 */
internal object LauncherIcon {
  /** Follows [themes] until cancelled. PackageManager calls block, so call it off the main thread. */
  suspend fun follow(context: Context, themes: Flow<AppTheme>) {
    themes.collect { show(context, it) }
  }

  private fun show(context: Context, theme: AppTheme) {
    val shown = entry(theme)
    // The new entry is enabled before the others go, so the app is never left without one.
    for (entry in Entry.entries.sortedByDescending { it == shown }) {
      val component = ComponentName(context, "$PACKAGE.${entry.alias}")
      val wanted = when {
        entry == shown && entry.enabledByDefault -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        entry == shown -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        entry.enabledByDefault -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
      }
      val pm = context.packageManager
      if (pm.getComponentEnabledSetting(component) == wanted) continue
      pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
    }
  }

  private fun entry(theme: AppTheme): Entry = when (theme) {
    AppTheme.System, AppTheme.Porcelain -> Entry.Default
    AppTheme.Clay -> Entry.Clay
    AppTheme.Cobalt -> Entry.Cobalt
    AppTheme.Stone -> Entry.Stone
    AppTheme.Obsidian -> Entry.Obsidian
  }

  /** A launcher entry: its alias in the manifest, and whether the manifest enables it, as only the default one is. */
  private enum class Entry(val alias: String, val enabledByDefault: Boolean = false) {
    Default("Launcher", enabledByDefault = true),
    Clay("LauncherClay"),
    Cobalt("LauncherCobalt"),
    Stone("LauncherStone"),
    Obsidian("LauncherObsidian"),
  }

  /** The aliases' class names sit in the source package, which the debug build's package name doesn't match. */
  private val PACKAGE = MainActivity::class.java.name.substringBeforeLast('.')
}
