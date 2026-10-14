package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/**
 * An [AlertDialog] with a confirm and a dismiss button. [onCancel] (back or outside tap) defaults to
 * [onDismiss]; Pi's `confirm` tells "No" and "cancelled" apart.
 */
@Composable
internal fun ConfirmDialog(
  title: String,
  text: String?,
  confirmLabel: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  dismissLabel: String = stringResource(R.string.chat_cancel),
  destructive: Boolean = false,
  onCancel: () -> Unit = onDismiss,
) {
  val colors = pipkinColors
  AlertDialog(
    onDismissRequest = onCancel,
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(text = confirmLabel, color = if (destructive) colors.destructive else colors.accent)
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(text = dismissLabel, color = colors.foreground) } },
    title = { Text(text = title, fontSize = PipkinTokens.FontSize.xl) },
    text = text?.let { { Text(text = it, fontSize = PipkinTokens.FontSize.base) } },
    containerColor = colors.surface1,
    titleContentColor = colors.foreground,
    textContentColor = colors.foregroundMuted,
  )
}

/**
 * A dialog asking for one line, or several when [multiline], with the cursor after [initial] and the keyboard up;
 * confirm is disabled while the text is blank.
 */
@Composable
internal fun TextInputDialog(
  title: String,
  initial: String,
  placeholder: String?,
  confirmLabel: String,
  multiline: Boolean,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val colors = pipkinColors
  var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = { onConfirm(value.text) }, enabled = value.text.isNotBlank()) {
        Text(text = confirmLabel)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.chat_cancel), color = colors.foreground) }
    },
    title = { Text(text = title, fontSize = PipkinTokens.FontSize.xl) },
    text = {
      // In the dialog's own composition, so the field is attached when the effect runs.
      val focusRequester = remember { FocusRequester() }
      LaunchedEffect(Unit) { focusRequester.requestFocus() }
      OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        modifier = Modifier
          .fillMaxWidth()
          .then(if (multiline) Modifier.heightIn(min = 160.dp) else Modifier)
          .focusRequester(focusRequester),
        placeholder = placeholder?.let { { Text(text = it, color = colors.foregroundMuted) } },
        singleLine = !multiline,
      )
    },
    containerColor = colors.surface1,
    titleContentColor = colors.foreground,
    textContentColor = colors.foreground,
  )
}
