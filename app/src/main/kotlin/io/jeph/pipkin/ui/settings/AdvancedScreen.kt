package io.jeph.pipkin.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.IdleStop
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** How many chats keep Pi running, and how long an idle one keeps it. */
@Composable
internal fun AdvancedScreen(onBack: () -> Unit) {
  val preferences = LocalContext.current.appGraph.preferences
  val maxRunning by preferences.maxRunningChats.collectAsStateWithLifecycle()
  val idleStop by preferences.idleStop.collectAsStateWithLifecycle()
  SettingsPage(title = stringResource(R.string.settings_advanced), onBack = onBack) {
    SettingsSection(title = null) {
      SettingsRow(title = stringResource(R.string.settings_running_chats)) {
        Stepper(value = maxRunning, range = AgentRegistry.RUNNING_LIMITS, onChange = preferences::setMaxRunningChats)
      }
    }
    SettingsCaption(text = stringResource(R.string.settings_running_chats_caption, AgentRegistry.DEFAULT_MAX_RUNNING))
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

/** A number between minus and plus buttons, kept within [range]. */
@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
  val colors = pipkinColors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    IconButton28(
      icon = R.drawable.ic_lucide_minus,
      contentDescription = stringResource(R.string.settings_running_chats_fewer),
      onClick = { onChange(value - 1) },
      enabled = value > range.first,
      tint = colors.foregroundMuted,
    )
    Text(
      text = value.toString(),
      // Announced as it changes, since the buttons on either side only say which way they go.
      modifier = Modifier.widthIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
      textAlign = TextAlign.Center,
    )
    IconButton28(
      icon = R.drawable.ic_lucide_plus,
      contentDescription = stringResource(R.string.settings_running_chats_more),
      onClick = { onChange(value + 1) },
      enabled = value < range.last,
      tint = colors.foregroundMuted,
    )
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
