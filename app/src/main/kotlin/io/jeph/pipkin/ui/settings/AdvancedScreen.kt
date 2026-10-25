package io.jeph.pipkin.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.IdleStop
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.AutoArchive
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** How many chats keep Pi running, how long an idle one keeps it, and when untouched chats are archived. */
@Composable
internal fun AdvancedScreen(onBack: () -> Unit) {
  val preferences = LocalContext.current.appGraph.preferences
  val maxRunning by preferences.maxRunningChats.collectAsStateWithLifecycle()
  val idleStop by preferences.idleStop.collectAsStateWithLifecycle()
  SettingsPage(title = stringResource(R.string.settings_advanced), onBack = onBack) {
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
    AutoArchiveSection(preferences)
  }
}

/** Auto archive's switch, and while it is on, how many days a chat may go untouched. */
@Composable
private fun AutoArchiveSection(preferences: Preferences) {
  val enabled by preferences.autoArchive.collectAsStateWithLifecycle()
  val days by preferences.autoArchiveDays.collectAsStateWithLifecycle()
  SettingsSection(title = null) {
    SettingsRow(
      title = stringResource(R.string.settings_auto_archive),
      modifier = Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = preferences::setAutoArchive),
    ) {
      SettingsSwitch(checked = enabled)
    }
    if (enabled) {
      SettingsDivider()
      SettingsRow(title = stringResource(R.string.settings_auto_archive_days)) {
        Stepper(
          value = days,
          range = AutoArchive.DAYS,
          onChange = preferences::setAutoArchiveDays,
          fewer = stringResource(R.string.settings_auto_archive_fewer),
          more = stringResource(R.string.settings_auto_archive_more),
        )
      }
    }
  }
  SettingsCaption(text = stringResource(R.string.settings_auto_archive_caption))
}

/** A number between minus and plus buttons labelled [fewer] and [more], kept within [range]. */
@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit, fewer: String, more: String) {
  val colors = pipkinColors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    RoundIconButton(
      icon = R.drawable.ic_lucide_minus,
      contentDescription = fewer,
      onClick = { onChange(value - 1) },
      modifier = Modifier.size(32.dp),
      enabled = value > range.first,
      background = colors.surface2,
      tint = colors.foreground,
    )
    Text(
      text = value.toString(),
      // Announced as it changes, since the buttons on either side only say which way they go.
      modifier = Modifier.widthIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
      fontWeight = FontWeight.Bold,
      style = LocalTextStyle.current.merge(TextStyle(fontFeatureSettings = PipkinFonts.TABULAR)),
      textAlign = TextAlign.Center,
    )
    RoundIconButton(
      icon = R.drawable.ic_lucide_plus,
      contentDescription = more,
      onClick = { onChange(value + 1) },
      modifier = Modifier.size(32.dp),
      enabled = value < range.last,
      background = colors.surface2,
      tint = colors.foreground,
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
