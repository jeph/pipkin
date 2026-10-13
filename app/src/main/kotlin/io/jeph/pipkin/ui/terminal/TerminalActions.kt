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

private enum class Stage { Menu, Rename, ConfirmClose }

/**
 * A terminal's actions sheet: New terminal and Restart shell when given, Rename, and Close terminal, which asks first,
 * since closing stops what runs in it. [onClose] runs once the user confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalActionsSheet(
  session: TerminalSession,
  onNewTerminal: (() -> Unit)?,
  onRestart: (() -> Unit)?,
  onClose: () -> Unit,
  onDismiss: () -> Unit,
) {
  var stage by rememberSaveable(session.id) { mutableStateOf(Stage.Menu) }
  val dismissThen = { action: () -> Unit ->
    onDismiss()
    action()
  }
  when (stage) {
    Stage.Menu -> PipkinSheet(onDismissRequest = onDismiss) {
      onNewTerminal?.let {
        SheetRow(
          label = stringResource(R.string.terminal_new),
          icon = R.drawable.ic_lucide_square_terminal,
          onClick = { dismissThen(it) },
        )
      }
      SheetRow(
        label = stringResource(R.string.terminal_rename),
        icon = R.drawable.ic_lucide_pencil,
        onClick = { stage = Stage.Rename },
      )
      onRestart?.let {
        SheetRow(
          label = stringResource(R.string.terminal_restart),
          icon = R.drawable.ic_lucide_refresh_cw,
          onClick = { dismissThen(it) },
        )
      }
      SheetRow(
        label = stringResource(R.string.terminal_close),
        icon = R.drawable.ic_lucide_x,
        labelColor = pipkinColors.destructive,
        onClick = { stage = Stage.ConfirmClose },
      )
    }

    Stage.Rename -> RenameDialog(
      title = stringResource(R.string.terminal_rename_title),
      initial = terminalTitle(session),
      onDismiss = onDismiss,
      onConfirm = { dismissThen { session.rename(it) } },
    )

    Stage.ConfirmClose -> ConfirmDialog(
      title = stringResource(R.string.terminal_close_confirm_title),
      text = stringResource(R.string.terminal_close_confirm_text),
      confirmLabel = stringResource(R.string.terminal_close_confirm),
      onConfirm = { dismissThen(onClose) },
      onDismiss = onDismiss,
      destructive = true,
    )
  }
}
