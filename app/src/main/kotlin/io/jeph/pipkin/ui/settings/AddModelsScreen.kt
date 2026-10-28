package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SearchField
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/**
 * Lists what the endpoint, the provider or Pi offers, narrowed by a search; tapping a model adds it to the
 * provider or takes it off again. The custom model field comes first, so it is never below hundreds of models.
 */
@Composable
internal fun AddModelsScreen(draft: ModelDraft, onBack: () -> Unit) {
  val accounts = LocalContext.current.appGraph.accountRepository
  val scope = rememberCoroutineScope()
  val failed = stringResource(R.string.provider_failed)
  var query by rememberSaveable { mutableStateOf("") }
  LaunchedEffect(draft) { draft.loadAvailable(accounts, failed) }
  // Derived, so typing in the search or toggling a model doesn't rebuild a list of hundreds.
  val available by remember(draft) { derivedStateOf { draft.available } }
  val shown by remember(draft) { derivedStateOf { available.filter { matches(it, query.trim()) } } }
  val custom by remember(draft) {
    derivedStateOf {
      val availableIds = available.mapTo(HashSet()) { it.id }
      draft.added.filter { it.id !in availableIds }.map(ModelForm::preview)
    }
  }
  LazySettingsPage(title = stringResource(R.string.provider_add_models), onBack = onBack) {
    item { FieldLabel(stringResource(R.string.models_custom), SettingsItemWidth) }
    modelRows(custom, draft)
    item {
      CustomModelField(
        input = draft.custom,
        modifier = SpacedItem,
        onAdd = {
          scope.launch {
            draft.addCustom(
              lookup = { id -> accounts.modelDefaults(listOf(id), draft.api).firstOrNull() ?: ModelConfig(id) },
              fallbackError = failed,
            )
          }
        },
      )
    }
    item { FieldLabel(stringResource(R.string.models_available), SpacedItem) }
    item {
      SearchField(
        query,
        onQueryChange = { query = it },
        placeholder = stringResource(R.string.chat_model_search),
        modifier = SpacedItem,
      )
    }
    availableRows(draft, available, shown)
    item { PillButton(label = stringResource(R.string.models_done), onClick = onBack, modifier = SpacedItem) }
  }
}

/** A settings page item [SettingsItemWidth] wide, set apart from the one above. */
private val SpacedItem = SettingsItemWidth.padding(top = PipkinTokens.Spacing.s12)

/** The provider's models matching the search, or why there are none to show. */
private fun LazyListScope.availableRows(draft: ModelDraft, available: List<ModelConfig>, shown: List<ModelConfig>) {
  draft.availableError?.let { error ->
    item {
      SettingsCaption(
        text = stringResource(R.string.models_list_failed, error),
        modifier = SpacedItem,
        color = pipkinColors.destructive,
      )
    }
  }
  when {
    draft.loadingAvailable -> item {
      Box(modifier = SpacedItem, contentAlignment = Alignment.Center) { SimmeringPot() }
    }

    available.isEmpty() -> item { SettingsCaption(text = stringResource(R.string.models_none), modifier = SpacedItem) }

    shown.isEmpty() -> item {
      SettingsCaption(text = stringResource(R.string.chat_model_search_empty), modifier = SpacedItem)
    }

    else -> modelRows(shown, draft)
  }
}

/** Case-insensitive match on the model's id or name. */
private fun matches(model: ModelConfig, term: String): Boolean = term.isEmpty() ||
  model.id.contains(term, ignoreCase = true) || model.name?.contains(term, ignoreCase = true) == true

/**
 * The models as one settings card, a lazy item per row. Unkeyed, since an endpoint may list an id twice and
 * duplicate keys would crash the list.
 */
private fun LazyListScope.modelRows(models: List<ModelConfig>, draft: ModelDraft) {
  itemsIndexed(models) { index, config ->
    val radius = PipkinTokens.Radius.xl
    val top = if (index == 0) radius else 0.dp
    val bottom = if (index == models.lastIndex) radius else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
    Column(
      modifier = SettingsItemWidth
        .padding(top = if (index == 0) PipkinTokens.Spacing.s12 else 0.dp)
        .clip(shape)
        .background(pipkinColors.surface1),
    ) {
      if (index > 0) SettingsDivider()
      ModelPickRow(config, draft)
    }
  }
}

@Composable
private fun ModelPickRow(config: ModelConfig, draft: ModelDraft) {
  val added = draft.isAdded(config.id)
  SettingsRow(
    title = config.name ?: config.id,
    subtitle = modelSubtitle(config),
    onClick = { draft.toggle(config) },
  ) {
    LucideIcon(
      icon = if (added) R.drawable.ic_lucide_check else R.drawable.ic_lucide_plus,
      contentDescription = null,
      tint = if (added) pipkinColors.accent else pipkinColors.foregroundMuted,
    )
  }
}

@Composable
private fun CustomModelField(input: CustomModelInput, modifier: Modifier, onAdd: () -> Unit) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
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
