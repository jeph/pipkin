package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.ProviderConfig
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.Spinner
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/** Adds ([id] null) or edits an OpenAI-compatible provider in Pi's `models.json`. */
@Composable
internal fun ProviderEditorScreen(id: String?, onBack: () -> Unit) {
  val accounts = LocalContext.current.appGraph.accountRepository
  val form = remember(id) { ProviderForm(editing = id != null, initialId = id.orEmpty()) }
  val status by accounts.status.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val failed = stringResource(R.string.provider_failed)
  var confirmRemove by remember { mutableStateOf(false) }
  LaunchedEffect(id) {
    if (id != null && accounts.status.value == null) accounts.refresh()
  }
  LaunchedEffect(status) {
    status?.custom?.firstOrNull { it.id == id }?.let(form::prefill)
  }
  Column(modifier = Modifier.fillMaxSize().background(pipkinColors.surface0)) {
    HeaderBar(
      title = id ?: stringResource(R.string.provider_title_new),
      leading = {
        IconButton28(
          icon = R.drawable.ic_lucide_chevron_left,
          contentDescription = stringResource(R.string.settings_back),
          onClick = onBack,
        )
      },
    )
    ProviderFormContent(
      form = form,
      onFetch = { scope.launch { form.fetchModels(accounts, failed) } },
      onSave = { scope.launch { if (form.save(accounts, failed)) onBack() } },
      onRemove = { confirmRemove = true },
    )
  }
  if (confirmRemove) {
    RemoveDialog(
      id = form.id,
      onConfirm = {
        confirmRemove = false
        scope.launch { if (form.remove(accounts, failed)) onBack() }
      },
      onDismiss = { confirmRemove = false },
    )
  }
}

@Composable
private fun ProviderFormContent(form: ProviderForm, onFetch: () -> Unit, onSave: () -> Unit, onRemove: () -> Unit) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .navigationBarsPadding()
        .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s24),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier = Modifier.widthIn(max = PipkinTokens.contentMaxWidth).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s16),
    ) {
      IdAndUrlFields(form = form)
      ApiKeyField(form = form)
      ApiOptions(form = form)
      ModelsEditor(form = form, onFetch = onFetch)
      ProviderActions(form = form, onSave = onSave, onRemove = onRemove)
    }
  }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
  Text(text = text, modifier = modifier, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
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
private fun ApiOptions(form: ProviderForm) {
  val colors = pipkinColors
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    FieldLabel(stringResource(R.string.provider_api_type))
    SegmentedControl(
      options = listOf(ProviderConfig.API_COMPLETIONS, ProviderConfig.API_RESPONSES),
      selected = form.api,
      onSelect = { form.api = it },
    ) { api, isSelected ->
      Text(
        text =
          stringResource(
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
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .toggleable(value = form.images, role = Role.Switch, onValueChange = { form.images = it }),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(R.string.provider_images),
      modifier = Modifier.weight(1f),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
    )
    Switch(checked = form.images, onCheckedChange = null)
  }
}

@Composable
private fun ModelsEditor(form: ProviderForm, onFetch: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      FieldLabel(text = stringResource(R.string.provider_models), modifier = Modifier.weight(1f))
      OutlinedButton(onClick = onFetch, enabled = form.canFetch) {
        if (form.fetching) Spinner() else Text(stringResource(R.string.provider_fetch_models))
      }
    }
    form.fetchError?.let { SettingsCaption(text = it, color = pipkinColors.destructive) }
    if (form.models.isNotEmpty()) ModelList(form = form)
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      OutlinedTextField(
        value = form.manualModel,
        onValueChange = { form.manualModel = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text(stringResource(R.string.provider_add_model)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { form.addManualModel() }),
        singleLine = true,
      )
      IconButton28(
        icon = R.drawable.ic_lucide_plus,
        contentDescription = stringResource(R.string.provider_add_model),
        onClick = form::addManualModel,
        enabled = form.manualModel.isNotBlank(),
      )
    }
  }
}

@Composable
private fun ModelList(form: ProviderForm) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.lg)
  Column(
    modifier = Modifier.fillMaxWidth().clip(shape).background(colors.surface1).border(1.dp, colors.border, shape),
  ) {
    form.models.forEachIndexed { index, model ->
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .toggleable(value = model.checked, role = Role.Checkbox, onValueChange = { form.setChecked(index, it) })
            .padding(horizontal = PipkinTokens.Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(checked = model.checked, onCheckedChange = null)
        Text(
          text = model.id,
          modifier = Modifier.padding(start = PipkinTokens.Spacing.s8),
          color = colors.foreground,
          fontSize = PipkinTokens.FontSize.base,
        )
      }
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
