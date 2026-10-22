package io.jeph.pipkin.ui.settings

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.BackupOutcome
import io.jeph.pipkin.data.BackupTask
import io.jeph.pipkin.data.ConfigBackups
import io.jeph.pipkin.host.BackupInfo
import io.jeph.pipkin.host.Backups
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.TermuxBuild
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlinx.coroutines.launch

/** The backups list in Termux, as loaded, or why it could not be. */
private sealed interface BackupList {
  data object Loading : BackupList

  data class Loaded(val backups: Backups) : BackupList

  data class Failed(val message: String?) : BackupList
}

/**
 * Backs up Pi's configuration into Termux and restores or deletes those backups. The list reloads after every backup,
 * restore and deletion.
 */
@Composable
internal fun BackupsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val backups = context.appGraph.configBackups
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarStackState() }
  val task by backups.task.collectAsStateWithLifecycle()
  val outcome by backups.outcome.collectAsStateWithLifecycle()
  var list by remember { mutableStateOf<BackupList>(BackupList.Loading) }
  var reloads by remember { mutableIntStateOf(0) }
  var restoring by remember { mutableStateOf<BackupInfo?>(null) }
  var deleting by remember { mutableStateOf<BackupInfo?>(null) }
  val failed = stringResource(R.string.provider_failed)
  val idle = task == null
  LaunchedEffect(idle, reloads) {
    if (idle) list = loadBackups(backups)
  }
  SettingsPage(title = stringResource(R.string.backups_title), onBack = onBack, snackbar = snackbar) {
    TaskSection(task, list, onCreate = backups::create)
    outcome?.let { OutcomeCaption(it, failed) }
    SavedSection(
      list = list,
      enabled = idle,
      failed = failed,
      onRetry = { reloads++ },
      onRestore = { restoring = it },
      onDelete = { deleting = it },
    )
  }
  restoring?.let { backup ->
    ConfirmDialog(
      title = stringResource(R.string.backups_restore_title),
      text = stringResource(R.string.backups_restore_text, backupTime(context, backup)),
      confirmLabel = stringResource(R.string.backups_restore),
      destructive = true,
      icon = R.drawable.ic_lucide_archive_restore,
      onConfirm = {
        restoring = null
        backups.restore(backup.name)
      },
      onDismiss = { restoring = null },
    )
  }
  deleting?.let { backup ->
    DeleteDialog(
      backup = backup,
      onConfirm = {
        deleting = null
        scope.launch {
          val error = deleteBackup(backups, backup.name)
          reloads++
          if (error != null) snackbar.show(error.ifEmpty { failed })
        }
      },
      onDismiss = { deleting = null },
    )
  }
}

/** Names a file that isn't a readable backup, since its time is only when the file last changed. */
@Composable
private fun DeleteDialog(backup: BackupInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  val context = LocalContext.current
  ConfirmDialog(
    title = stringResource(R.string.backups_delete_title),
    text = if (backup.error != null) {
      stringResource(R.string.backups_delete_file_text, backup.name)
    } else {
      stringResource(R.string.backups_delete_text, backupTime(context, backup))
    },
    confirmLabel = stringResource(R.string.backups_delete),
    destructive = true,
    icon = R.drawable.ic_lucide_trash_2,
    onConfirm = onConfirm,
    onDismiss = onDismiss,
  )
}

private suspend fun loadBackups(backups: ConfigBackups): BackupList = try {
  BackupList.Loaded(backups.list())
} catch (failure: IOException) {
  BackupList.Failed(failure.message)
} catch (rejected: HostRejected) {
  BackupList.Failed(rejected.error)
}

/** Null once deleted; otherwise why not, empty when the host gave no reason. */
private suspend fun deleteBackup(backups: ConfigBackups, name: String): String? = try {
  backups.delete(name)
  null
} catch (failure: IOException) {
  failure.message.orEmpty()
} catch (rejected: HostRejected) {
  rejected.error
}

/** Back up now, and the backup or restore under way with its latest progress line. */
@Composable
private fun TaskSection(task: BackupTask?, list: BackupList, onCreate: () -> Unit) {
  val context = LocalContext.current
  SettingsSection(title = null) {
    SettingsRow(
      title = stringResource(R.string.backups_create),
      subtitle = (task as? BackupTask.Creating)?.let { stringResource(R.string.backups_creating) },
      enabled = task == null,
      onClick = onCreate,
    ) {
      if (task is BackupTask.Creating) SimmeringPot()
    }
    if (task is BackupTask.Restoring) {
      val backup = (list as? BackupList.Loaded)?.backups?.backups?.find { it.name == task.name }
      SettingsDivider()
      SettingsRow(
        title = stringResource(R.string.backups_restoring, backup?.let { backupTime(context, it) } ?: task.name),
      ) {
        SimmeringPot()
      }
    }
    task?.progress?.let { line ->
      ProgressLine(
        line,
        Modifier.padding(
          start = PipkinTokens.Spacing.s16,
          end = PipkinTokens.Spacing.s16,
          bottom = PipkinTokens.Spacing.s12,
        ),
      )
    }
  }
}

@Composable
private fun SavedSection(
  list: BackupList,
  enabled: Boolean,
  failed: String,
  onRetry: () -> Unit,
  onRestore: (BackupInfo) -> Unit,
  onDelete: (BackupInfo) -> Unit,
) {
  SettingsSection(title = stringResource(R.string.backups_saved)) {
    when (list) {
      BackupList.Loading -> SettingsRow(title = stringResource(R.string.backups_loading)) { SimmeringPot() }

      is BackupList.Failed -> SettingsRow(
        title = list.message ?: failed,
        titleColor = pipkinColors.destructive,
        onClick = onRetry,
      ) {
        SettingsValue(stringResource(R.string.backups_retry))
      }

      is BackupList.Loaded -> {
        if (list.backups.backups.isEmpty()) SettingsRow(title = stringResource(R.string.backups_empty))
        list.backups.backups.forEachIndexed { index, backup ->
          if (index > 0) SettingsDivider()
          BackupRow(backup, enabled, onRestore = { onRestore(backup) }, onDelete = { onDelete(backup) })
        }
      }
    }
  }
  val dir = (list as? BackupList.Loaded)?.backups?.dir
  SettingsCaption(text = stringResource(R.string.backups_caption, dir ?: "~/.${TermuxBuild.NAME}/backups"))
}

/** One backup: when it was made, its size and Pi version, or why it can't be read; restorable only when readable. */
@Composable
private fun BackupRow(backup: BackupInfo, enabled: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
  val context = LocalContext.current
  val colors = pipkinColors
  val size = Formatter.formatShortFileSize(context, backup.size)
  val subtitle =
    when {
      backup.error != null -> stringResource(R.string.backups_unreadable, backup.name, backup.error.localized())
      backup.piVersion != null -> "$size · ${stringResource(R.string.settings_pi_version, backup.piVersion)}"
      else -> size
    }
  SettingsRow(title = backupTime(context, backup), subtitle = subtitle) {
    if (backup.error == null) {
      RoundIconButton(
        icon = R.drawable.ic_lucide_archive_restore,
        contentDescription = stringResource(R.string.backups_restore),
        onClick = onRestore,
        enabled = enabled,
        tint = colors.foregroundMuted,
      )
    }
    RoundIconButton(
      icon = R.drawable.ic_lucide_trash_2,
      contentDescription = stringResource(R.string.backups_delete),
      onClick = onDelete,
      enabled = enabled,
      tint = colors.foregroundMuted,
    )
  }
}

@Composable
private fun OutcomeCaption(outcome: BackupOutcome, failed: String) {
  val (text, color) =
    when (outcome) {
      BackupOutcome.Created -> stringResource(R.string.backups_created) to pipkinColors.foregroundMuted
      BackupOutcome.Restored -> stringResource(R.string.backups_restored) to pipkinColors.foregroundMuted
      is BackupOutcome.Failed -> (outcome.message ?: failed) to pipkinColors.destructive
    }
  SettingsCaption(text = text, color = color)
}

/** The latest line of a backup or restore's progress, on one monospace line. */
@Composable
private fun ProgressLine(line: String, modifier: Modifier = Modifier) {
  Text(
    text = line,
    modifier = modifier.fillMaxWidth(),
    color = pipkinColors.foregroundMuted,
    fontFamily = PipkinFonts.mono,
    fontSize = PipkinTokens.FontSize.code,
    lineHeight = PipkinTokens.LineHeight.code,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

private fun backupTime(context: Context, backup: BackupInfo): String = DateUtils.formatDateTime(
  context,
  backup.createdAt,
  DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or
    DateUtils.FORMAT_ABBREV_MONTH,
)
