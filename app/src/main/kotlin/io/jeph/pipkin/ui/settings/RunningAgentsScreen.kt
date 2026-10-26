package io.jeph.pipkin.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.IdleStop
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.pipkinColors

/** How many chats keep their agent running at once, and how long an idle one keeps it. */
@Composable
internal fun RunningAgentsScreen(onBack: () -> Unit) {
  val preferences = LocalContext.current.appGraph.preferences
  val maxRunning by preferences.maxRunningChats.collectAsStateWithLifecycle()
  val idleStop by preferences.idleStop.collectAsStateWithLifecycle()
  SettingsPage(title = stringResource(R.string.settings_running_agents), onBack = onBack) {
    SettingsSection(title = null) {
      SettingsRow(title = stringResource(R.string.settings_running_chats)) {
        Stepper(
          value = maxRunning,
          range = AgentRegistry.RUNNING_LIMITS,
          onChange = preferences::setMaxRunningChats,
          fewer = stringResource(R.string.settings_running_chats_fewer),
          more = stringResource(R.string.settings_running_chats_more),
        )
      }
    }
    SettingsCaption(
      text = pluralStringResource(
        R.plurals.settings_running_chats_caption,
        AgentRegistry.DEFAULT_MAX_RUNNING,
        AgentRegistry.DEFAULT_MAX_RUNNING,
      ),
    )
    SettingsSection(title = stringResource(R.string.settings_idle_stop)) {
      IdleStop.entries.forEachIndexed { index, stop ->
        if (index > 0) SettingsDivider()
        val selected = stop == idleStop
        SettingsRow(
          title = stringResource(stop.label),
          modifier =
            Modifier.selectable(selected = selected, role = Role.RadioButton) { preferences.setIdleStop(stop) },
        ) {
          if (selected) {
            LucideIcon(icon = R.drawable.ic_lucide_check, contentDescription = null, tint = pipkinColors.accent)
          }
        }
      }
    }
    SettingsCaption(text = stringResource(R.string.settings_idle_stop_caption))
  }
}

private val IdleStop.label: Int
  @StringRes get() = when (this) {
    IdleStop.OneMinute -> R.string.settings_idle_stop_1_minute
    IdleStop.FiveMinutes -> R.string.settings_idle_stop_5_minutes
    IdleStop.FifteenMinutes -> R.string.settings_idle_stop_15_minutes
    IdleStop.ThirtyMinutes -> R.string.settings_idle_stop_30_minutes
    IdleStop.Never -> R.string.settings_idle_stop_never
  }
