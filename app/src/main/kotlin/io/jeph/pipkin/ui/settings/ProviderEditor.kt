package io.jeph.pipkin.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.ProviderConfig
import io.jeph.pipkin.ui.components.Spinner
import io.jeph.pipkin.ui.components.StatusDot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/**
 * Adds or edits an OpenAI-compatible provider: connection details, a connection check that unlocks
 * adding models, and the added models. Nothing reaches `models.json` until Save; [onDone] runs after
 * a save or removal, [onBack] leaves (asking first when there are unsaved edits).
 */
@Composable
internal fun ProviderEditorScreen(
  form: ProviderForm,
  onBack: () -> Unit,
  onDone: () -> Unit,
  onAddModels: () -> Unit,
  onOpenModel: (modelId: String) -> Unit,
) {
  val accounts = LocalContext.current.appGraph.accountRepository
  val scope = rememberCoroutineScope()
  val failed = stringResource(R.string.provider_failed)
  var confirmRemove by remember { mutableStateOf(false) }
  var confirmDiscard by remember { mutableStateOf(false) }
  val leave = { if (form.dirty) confirmDiscard = true else onBack() }
  // Reads Pi's files afresh, so changes made outside Pipkin (for example by the agent) show here.
  LaunchedEffect(form) {
    if (!form.editing || form.prefilled) return@LaunchedEffect
    accounts.refresh()
    accounts.status.value?.custom?.firstOrNull { it.id == form.id }?.let(form::prefill)
  }
  // A saved provider's connection is checked on opening, so models can be added straight away.
  LaunchedEffect(form.prefilled) {
    if (form.prefilled && form.check == ConnectionCheck.Idle && form.canCheck) form.checkConnection(accounts, failed)
  }
  BackHandler(enabled = form.dirty) { confirmDiscard = true }
  SettingsPage(
    title = if (form.editing) form.id else stringResource(R.string.provider_title_new),
    onBack = leave,
  ) {
    IdAndUrlFields(form = form)
    ApiKeyField(form = form)
    ApiTypeField(form = form)
    ConnectionCheckRow(form = form, onCheck = { scope.launch { form.checkConnection(accounts, failed) } })
    ModelsSection(
      draft = form,
      emptyText = stringResource(
        if (form.connected) R.string.provider_models_empty else R.string.provider_check_needed,
      ),
      onAddModels = onAddModels,
      onOpenModel = onOpenModel,
    )
    ProviderActions(
      form = form,
      onSave = { scope.launch { if (form.save(accounts, failed)) onDone() } },
      onRemove = { confirmRemove = true },
    )
  }
  if (confirmRemove) {
    RemoveDialog(
      id = form.id,
      onConfirm = {
        confirmRemove = false
        scope.launch { if (form.removeProvider(accounts, failed)) onDone() }
      },
      onDismiss = { confirmRemove = false },
    )
  }
  if (confirmDiscard) {
    DiscardDialog(
      onDiscard = {
        confirmDiscard = false
        onBack()
      },
      onDismiss = { confirmDiscard = false },
    )
  }
}

@Composable
private fun IdAndUrlFields(form: ProviderForm) {
  val invalidId = stringResource(R.string.provider_id_invalid)
  val idError = form.idError ?: invalidId.takeIf { form.id.isNotEmpty() && !form.idValid }
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    FieldLabel(stringResource(R.string.provider_id))
    OutlinedTextField(
      value = form.id,
      onValueChange = { form.id = it.trim() },
      modifier = Modifier.fillMaxWidth(),
      readOnly = form.editing,
      placeholder = { Text(stringResource(R.string.provider_id_hint)) },
      supportingText = idError?.let { { Text(it) } },
      isError = idError != null,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
      singleLine = true,
    )
  }
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    FieldLabel(stringResource(R.string.provider_base_url))
    OutlinedTextField(
      value = form.baseUrl,
      onValueChange = { form.baseUrl = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text(stringResource(R.string.provider_base_url_hint)) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
      singleLine = true,
    )
  }
}

@Composable
private fun ApiKeyField(form: ProviderForm) {
  var visible by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    FieldLabel(stringResource(R.string.provider_api_key))
    OutlinedTextField(
      value = form.apiKey,
      onValueChange = { form.apiKey = it },
      modifier = Modifier.fillMaxWidth(),
      trailingIcon = {
        TextButton(onClick = { visible = !visible }) {
          Text(stringResource(if (visible) R.string.provider_hide_key else R.string.provider_show_key))
        }
      },
      visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
      singleLine = true,
    )
  }
}

@Composable
private fun ApiTypeField(form: ProviderForm) {
  val colors = pipkinColors
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    FieldLabel(stringResource(R.string.provider_api_type))
    SegmentedControl(
      options = listOf(ProviderConfig.API_COMPLETIONS, ProviderConfig.API_RESPONSES),
      selected = form.api,
      onSelect = { form.api = it },
    ) { api, isSelected ->
      Text(
        text = stringResource(
          if (api ==
            ProviderConfig.API_RESPONSES
          ) {
            R.string.provider_api_responses
          } else {
            R.string.provider_api_completions
          },
        ),
        color = if (isSelected) colors.foreground else colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.base,
      )
    }
  }
}

@Composable
private fun ConnectionCheckRow(form: ProviderForm, onCheck: () -> Unit) {
  val colors = pipkinColors
  val check = form.check
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth(), enabled = form.canCheck) {
      if (check is ConnectionCheck.Checking) Spinner() else Text(stringResource(R.string.provider_check))
    }
    when (check) {
      is ConnectionCheck.Ok -> Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
      ) {
        StatusDot(color = colors.dotSuccess)
        Text(
          text = pluralStringResource(R.plurals.provider_connected, check.models.size, check.models.size),
          color = colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.sm,
        )
      }

      is ConnectionCheck.Failed -> SettingsCaption(text = check.message, color = colors.destructive)

      ConnectionCheck.Idle, is ConnectionCheck.Checking -> Unit
    }
  }
}

@Composable
private fun ProviderActions(form: ProviderForm, onSave: () -> Unit, onRemove: () -> Unit) {
  val colors = pipkinColors
  form.formError?.let { SettingsCaption(text = it, color = colors.destructive) }
  Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), enabled = form.canSave) {
    if (form.saving) Spinner(tint = colors.accentForeground) else Text(stringResource(R.string.provider_save))
  }
  if (form.editing) {
    TextButton(
      onClick = onRemove,
      modifier = Modifier.fillMaxWidth(),
      enabled = !form.saving,
      colors = ButtonDefaults.textButtonColors(contentColor = colors.destructive),
    ) {
      Text(stringResource(R.string.provider_remove))
    }
  }
}

@Composable
private fun RemoveDialog(id: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  val colors = pipkinColors
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = colors.destructive)) {
        Text(stringResource(R.string.provider_remove))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_cancel)) } },
    title = { Text(stringResource(R.string.provider_remove_title, id)) },
    text = { Text(stringResource(R.string.provider_remove_text)) },
    containerColor = colors.surface1,
    titleContentColor = colors.foreground,
    textContentColor = colors.foregroundMuted,
  )
}
