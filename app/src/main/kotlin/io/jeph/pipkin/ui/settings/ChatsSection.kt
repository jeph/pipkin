package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.data.AutoArchive
import io.jeph.pipkin.data.Preferences

/**
 * How chats behave: whether one that finishes or needs an answer off screen shows in the pill (Android's own settings
 * cover the system notifications), and whether chats nobody touched for a set number of days are archived.
 */
@Composable
internal fun ChatsSection(preferences: Preferences) {
  val inAppNotifications by preferences.inAppNotifications.collectAsStateWithLifecycle()
  val autoArchive by preferences.autoArchive.collectAsStateWithLifecycle()
  val autoArchiveDays by preferences.autoArchiveDays.collectAsStateWithLifecycle()
  SettingsSection(title = stringResource(R.string.settings_section_chats)) {
    SettingsRow(
      title = stringResource(R.string.settings_in_app_notifications),
      subtitle = stringResource(R.string.settings_in_app_notifications_summary),
      modifier = Modifier.toggleable(
        value = inAppNotifications,
        role = Role.Switch,
        onValueChange = preferences::setInAppNotifications,
      ),
    ) {
      SettingsSwitch(checked = inAppNotifications)
    }
    SettingsDivider()
    SettingsRow(
      title = stringResource(R.string.settings_auto_archive),
      subtitle = stringResource(R.string.settings_auto_archive_summary),
      modifier = Modifier.toggleable(
        value = autoArchive,
        role = Role.Switch,
        onValueChange = preferences::setAutoArchive,
      ),
    ) {
      SettingsSwitch(checked = autoArchive)
    }
    if (autoArchive) {
      SettingsDivider()
      SettingsRow(title = stringResource(R.string.settings_auto_archive_days)) {
        Stepper(
          value = autoArchiveDays,
          range = AutoArchive.DAYS,
          onChange = preferences::setAutoArchiveDays,
          fewer = stringResource(R.string.settings_auto_archive_fewer),
          more = stringResource(R.string.settings_auto_archive_more),
        )
      }
    }
  }
}
