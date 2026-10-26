package io.jeph.pipkin.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.jeph.pipkin.R
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.ui.components.DialogConfirm
import io.jeph.pipkin.ui.components.DialogTextField
import io.jeph.pipkin.ui.components.PipkinDialog
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow

/**
 * A chat's sheet in the tab menu (Open in split screen when [onOpenInSplit] is given, Rename, Archive) and the rename
 * dialog it leads to, which opens once the sheet has slid away.
 */
@Composable
internal fun SessionActions(
  session: SessionInfo,
  onDismiss: () -> Unit,
  onRename: (String) -> Unit,
  onArchive: () -> Unit,
  onOpenInSplit: (() -> Unit)?,
) {
  var menuOpen by rememberSaveable(session.path) { mutableStateOf(true) }
  var renaming by rememberSaveable(session.path) { mutableStateOf(false) }
  if (menuOpen) {
    PipkinSheet(
      onDismissRequest = {
        menuOpen = false
        if (!renaming) onDismiss()
      },
    ) { dismiss ->
      if (onOpenInSplit != null) {
        SheetRow(
          label = stringResource(R.string.shell_split_open),
          icon = R.drawable.ic_lucide_columns_2,
          onClick = {
            onOpenInSplit()
            dismiss {}
          },
        )
      }
      SheetRow(
        label = stringResource(R.string.shell_session_rename),
        icon = R.drawable.ic_lucide_pencil,
        onClick = { dismiss { renaming = true } },
      )
      SheetRow(
        label = stringResource(R.string.archive_action),
        icon = R.drawable.ic_lucide_archive,
        onClick = { dismiss(onArchive) },
      )
    }
  }
  if (!renaming) return
  RenameDialog(
    title = stringResource(R.string.shell_rename_title),
    initial = session.name.orEmpty(),
    onDismiss = onDismiss,
    onConfirm = { name ->
      onRename(name)
      onDismiss()
    },
  )
}

/**
 * Asks for a new name, starting from [initial] with the cursor after it and the keyboard up; Save trims it and is
 * disabled while it is blank.
 */
@Composable
internal fun RenameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
  var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
    mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
  }
  PipkinDialog(
    title = title,
    confirm = DialogConfirm(
      label = stringResource(R.string.shell_rename_save),
      onClick = { onConfirm(name.text.trim()) },
      enabled = name.text.isNotBlank(),
    ),
    dismissLabel = stringResource(R.string.shell_cancel),
    onDismiss = onDismiss,
  ) {
    // In the dialog's own composition, so the field is attached when the effect runs.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    DialogTextField(
      value = name,
      onValueChange = { name = it },
      modifier = Modifier.focusRequester(focusRequester),
      placeholder = stringResource(R.string.shell_rename_label),
    )
  }
}
