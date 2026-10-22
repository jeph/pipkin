package io.jeph.pipkin.ui.terminal

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.terminal.TerminalSession
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.RenameDialog

/** The name the user gave [session], else "Terminal 2" and so on. */
@Composable
internal fun terminalTitle(session: TerminalSession): String {
  val name by session.name.collectAsStateWithLifecycle()
  return name ?: stringResource(R.string.terminal_title, session.number)
}

/** A dialog a terminal's actions open: renaming it, or confirming that closing it stops what runs in it. */
internal enum class TerminalDialog { Rename, ConfirmClose }

/**
 * A terminal tab's actions sheet in the session sheet's tab rows: Rename and Close terminal, which asks first, since
 * closing stops what runs in it. [onClose] runs once the user confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalActionsSheet(session: TerminalSession, onClose: () -> Unit, onDismiss: () -> Unit) {
  var dialog by rememberSaveable(session.id) { mutableStateOf<TerminalDialog?>(null) }
  dialog?.let {
    TerminalDialogs(session, it, onClose, onDismiss)
    return
  }
  PipkinSheet(onDismissRequest = onDismiss) {
    SheetRow(
      label = stringResource(R.string.terminal_rename),
      icon = R.drawable.ic_lucide_pencil,
      onClick = { dialog = TerminalDialog.Rename },
    )
    SheetRow(
      label = stringResource(R.string.terminal_close),
      icon = R.drawable.ic_lucide_x,
      labelColor = pipkinColors.destructive,
      onClick = { dialog = TerminalDialog.ConfirmClose },
    )
  }
}

/** [dialog] for [session]; [onClose] runs once the user confirms closing, and [onDismiss] closes the dialog. */
@Composable
internal fun TerminalDialogs(
  session: TerminalSession,
  dialog: TerminalDialog,
  onClose: () -> Unit,
  onDismiss: () -> Unit,
) {
  val name by session.name.collectAsStateWithLifecycle()
  when (dialog) {
    TerminalDialog.Rename -> RenameDialog(
      title = stringResource(R.string.terminal_rename_title),
      initial = name.orEmpty(),
      onDismiss = onDismiss,
      onConfirm = {
        onDismiss()
        session.rename(it)
      },
    )

    TerminalDialog.ConfirmClose -> ConfirmDialog(
      title = stringResource(R.string.terminal_close_confirm_title),
      text = stringResource(R.string.terminal_close_confirm_text),
      confirmLabel = stringResource(R.string.terminal_close_confirm),
      onConfirm = {
        onDismiss()
        onClose()
      },
      onDismiss = onDismiss,
      destructive = true,
      icon = R.drawable.ic_lucide_square_terminal,
    )
  }
}
