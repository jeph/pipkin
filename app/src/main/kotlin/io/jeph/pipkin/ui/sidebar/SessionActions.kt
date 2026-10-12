package io.jeph.pipkin.ui.sidebar

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.jeph.pipkin.R
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.theme.pipkinColors

private enum class Stage { Menu, Rename }

/** The sidebar's per-chat sheet (Rename, Archive) and the rename dialog it leads to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionActions(
  session: SessionInfo,
  onDismiss: () -> Unit,
  onRename: (String) -> Unit,
  onArchive: () -> Unit,
) {
  var stage by rememberSaveable(session.path) { mutableStateOf(Stage.Menu) }
  when (stage) {
    Stage.Menu -> PipkinSheet(onDismissRequest = onDismiss) {
      SheetRow(
        label = stringResource(R.string.shell_session_rename),
        icon = R.drawable.ic_lucide_pencil,
        onClick = { stage = Stage.Rename },
      )
      SheetRow(
        label = stringResource(R.string.archive_action),
        icon = R.drawable.ic_lucide_archive,
        onClick = {
          onDismiss()
          onArchive()
        },
      )
    }

    Stage.Rename -> RenameDialog(
      initial = session.name.orEmpty(),
      onDismiss = onDismiss,
      onConfirm = { name ->
        onRename(name)
        onDismiss()
      },
    )
  }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
  var name by rememberSaveable { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = pipkinColors.surface1,
    title = { Text(stringResource(R.string.shell_rename_title)) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        singleLine = true,
        label = { Text(stringResource(R.string.shell_rename_label)) },
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
        Text(stringResource(R.string.shell_rename_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) }
    },
  )
}
