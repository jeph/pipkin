package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.ui.components.DialogConfirm
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.components.PipkinDialog
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SearchField
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

/**
 * Lists what the endpoint, the provider or Pi offers, narrowed by a search; tapping a model adds it to the
 * provider or takes it off again.
 */
@Composable
internal fun AddModelsScreen(draft: ModelDraft, onBack: () -> Unit) {
  val accounts = LocalContext.current.appGraph.accountRepository
  val scope = rememberCoroutineScope()
  val failed = stringResource(R.string.provider_failed)
  var query by rememberSaveable { mutableStateOf("") }
  LaunchedEffect(draft) { draft.loadAvailable(accounts, failed) }
  SettingsPage(title = stringResource(R.string.provider_add_models), onBack = onBack) {
    val available = draft.available
    val availableIds = available.mapTo(HashSet()) { it.id }
    SearchField(query, onQueryChange = { query = it }, placeholder = stringResource(R.string.chat_model_search))
    FieldLabel(stringResource(R.string.models_available))
    val term = query.trim()
    val shown = remember(available, term) {
      available.filter {
        term.isEmpty() || it.id.contains(term, ignoreCase = true) ||
          it.name?.contains(term, ignoreCase = true) == true
      }
    }
    draft.availableError?.let {
      SettingsCaption(text = stringResource(R.string.models_list_failed, it), color = pipkinColors.destructive)
    }
    when {
      draft.loadingAvailable -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SimmeringPot()
      }

      available.isEmpty() -> SettingsCaption(text = stringResource(R.string.models_none))

      shown.isEmpty() -> SettingsCaption(text = stringResource(R.string.chat_model_search_empty))

      else -> ModelPickList(models = shown, draft = draft)
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
    PillButton(label = stringResource(R.string.models_done), onClick = onBack, modifier = Modifier.fillMaxWidth())
  }
}

/** Asks before dropping unsaved edits. */
@Composable
internal fun DiscardDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
  PipkinDialog(
    title = stringResource(R.string.provider_discard_title),
    text = stringResource(R.string.provider_discard_text),
    icon = R.drawable.ic_lucide_trash_2,
    confirm = DialogConfirm(stringResource(R.string.provider_discard), onDiscard, destructive = true),
    dismissLabel = stringResource(R.string.provider_keep_editing),
    onDismiss = onDismiss,
  )
}

@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier,
    color = pipkinColors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.sm,
    fontWeight = FontWeight.SemiBold,
  )
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
    SettingsSwitch(checked = checked)
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
    // A compact count reads as one short figure: 1000K, not 1,000K.
    isGroupingUsed = false
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
        shape = SettingsFieldShape,
        colors = settingsFieldColors(),
        placeholder = { Text(stringResource(R.string.models_custom_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAdd() }),
        singleLine = true,
      )
      if (input.adding) {
        SimmeringPot()
      } else {
        RoundIconButton(
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
