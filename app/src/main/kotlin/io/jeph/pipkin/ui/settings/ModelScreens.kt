package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import kotlinx.coroutines.launch

/** The provider page's models: the add-models button and one row per added model. */
@Composable
internal fun ModelsSection(
  draft: ModelDraft,
  emptyText: String,
  onAddModels: () -> Unit,
  onOpenModel: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      FieldLabel(text = stringResource(R.string.provider_models), modifier = Modifier.weight(1f))
      SmallButton(
        label = stringResource(R.string.provider_add_models),
        onClick = onAddModels,
        enabled = draft.canAddModels,
      )
    }
    val added = draft.added
    if (added.isEmpty()) {
      SettingsCaption(text = emptyText)
    } else {
      SettingsSection(title = null) {
        added.forEachIndexed { index, model ->
          if (index > 0) SettingsDivider()
          val preview = model.preview()
          SettingsRow(
            title = preview.name ?: preview.id,
            subtitle = if (model.valid) modelSubtitle(preview) else stringResource(R.string.provider_model_invalid),
            onClick = { onOpenModel(model.id) },
          ) { Chevron() }
        }
      }
    }
  }
}

/** Lists what the endpoint or Pi offers; tapping a model adds it to the provider or takes it off again. */
@Composable
internal fun AddModelsScreen(draft: ModelDraft, onBack: () -> Unit) {
  val accounts = LocalContext.current.appGraph.accountRepository
  val scope = rememberCoroutineScope()
  val failed = stringResource(R.string.provider_failed)
  SettingsPage(title = stringResource(R.string.provider_add_models), onBack = onBack) {
    val available = draft.available
    val availableIds = available.mapTo(HashSet()) { it.id }
    FieldLabel(stringResource(R.string.models_available))
    if (available.isEmpty()) {
      SettingsCaption(text = stringResource(R.string.models_none))
    } else {
      ModelPickList(models = available, draft = draft)
    }
    FieldLabel(stringResource(R.string.models_custom))
    val custom = draft.added.filter { it.id !in availableIds }.map(ModelForm::preview)
    if (custom.isNotEmpty()) ModelPickList(models = custom, draft = draft)
    CustomModelField(
      input = draft.custom,
      onAdd = {
        scope.launch {
          draft.addCustom(
            lookup = { id -> accounts.modelDefaults(listOf(id), draft.api).firstOrNull() ?: ModelConfig(id) },
            fallbackError = failed,
          )
        }
      },
    )
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_done)) }
  }
}

/** Asks before dropping unsaved edits. */
@Composable
internal fun DiscardDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
  val colors = pipkinColors
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = onDiscard, colors = ButtonDefaults.textButtonColors(contentColor = colors.destructive)) {
        Text(stringResource(R.string.provider_discard))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_keep_editing)) } },
    title = { Text(stringResource(R.string.provider_discard_title)) },
    text = { Text(stringResource(R.string.provider_discard_text)) },
    containerColor = colors.surface1,
    titleContentColor = colors.foreground,
    textContentColor = colors.foregroundMuted,
  )
}

@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
  Text(text = text, modifier = modifier, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      modifier = Modifier.weight(1f),
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.base,
    )
    Switch(checked = checked, onCheckedChange = null)
  }
}

/** The model's name (or id), then its id when named, thinking, images and context window. */
@Composable
internal fun modelSubtitle(config: ModelConfig): String? {
  val parts = buildList {
    if (config.name != null) add(config.id)
    if (config.reasoning == true) add(stringResource(R.string.model_summary_thinking))
    if (config.images == true) add(stringResource(R.string.model_summary_images))
    config.contextWindow?.let { add(stringResource(R.string.model_summary_context, formatTokens(it))) }
  }
  return parts.joinToString(stringResource(R.string.separator_dot)).ifEmpty { null }
}

/** 272000 → "272K", 1050000 → "1.05M", with the app language's digits and unit. */
@Composable
internal fun formatTokens(count: Long): String {
  val numbers = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = 2
    roundingMode = RoundingMode.HALF_UP
  }
  return when {
    count >= MILLION -> stringResource(R.string.model_tokens_millions, numbers.format(BigDecimal.valueOf(count, 6)))
    count >= THOUSAND -> stringResource(R.string.model_tokens_thousands, numbers.format(BigDecimal.valueOf(count, 3)))
    else -> numbers.format(count)
  }
}

private const val THOUSAND = 1_000L
private const val MILLION = 1_000_000L

@Composable
private fun ModelPickList(models: List<ModelConfig>, draft: ModelDraft) {
  val colors = pipkinColors
  SettingsSection(title = null) {
    models.forEachIndexed { index, config ->
      if (index > 0) SettingsDivider()
      val added = draft.isAdded(config.id)
      SettingsRow(
        title = config.name ?: config.id,
        subtitle = modelSubtitle(config),
        onClick = { draft.toggle(config) },
      ) {
        LucideIcon(
          icon = if (added) R.drawable.ic_lucide_check else R.drawable.ic_lucide_plus,
          contentDescription = null,
          tint = if (added) colors.accent else colors.foregroundMuted,
        )
      }
    }
  }
}

@Composable
private fun CustomModelField(input: CustomModelInput, onAdd: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      OutlinedTextField(
        value = input.text,
        onValueChange = { input.text = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text(stringResource(R.string.models_custom_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAdd() }),
        singleLine = true,
      )
      if (input.adding) {
        SimmeringPot()
      } else {
        IconButton28(
          icon = R.drawable.ic_lucide_plus,
          contentDescription = stringResource(R.string.models_custom_add),
          onClick = onAdd,
          enabled = input.text.isNotBlank(),
        )
      }
    }
    input.error?.let { SettingsCaption(text = it, color = pipkinColors.destructive) }
  }
}
