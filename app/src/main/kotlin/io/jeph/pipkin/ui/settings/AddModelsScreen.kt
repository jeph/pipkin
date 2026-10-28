package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import io.jeph.pipkin.ui.components.SearchField
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/**
 * Lists the provider's custom models, then what the endpoint, the provider or Pi offers, narrowed by a search; tapping
 * a model adds it to the provider or takes it off again. A search that isn't a listed model's id offers to add it as a
 * custom model.
 */
@Composable
internal fun AddModelsScreen(draft: ModelDraft, onBack: () -> Unit) {
  val accounts = LocalContext.current.appGraph.accountRepository
  val scope = rememberCoroutineScope()
  val failed = stringResource(R.string.provider_failed)
  var query by rememberSaveable { mutableStateOf("") }
  LaunchedEffect(draft) { draft.loadAvailable(accounts, failed) }
  // Derived, so typing in the search or toggling a model doesn't rebuild a list of hundreds.
  val models by remember(draft) {
    derivedStateOf {
      val available = draft.available
      val availableIds = available.mapTo(HashSet()) { it.id }
      draft.added.filter { it.id !in availableIds }.map(ModelForm::preview) + available
    }
  }
  val shown by remember(draft) { derivedStateOf { models.filter { matches(it, query.trim()) } } }
  val newId by remember(draft) {
    derivedStateOf { query.trim().takeIf { id -> id.isNotEmpty() && models.none { it.id == id } } }
  }
  LazySettingsPage(title = stringResource(R.string.provider_add_models), onBack = onBack) {
    item {
      SearchField(
        query,
        onQueryChange = { query = it },
        placeholder = stringResource(R.string.models_search),
        modifier = SettingsItemWidth,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search),
      )
    }
    newId?.let { modelId ->
      item {
        AddCustomModelRow(modelId, draft.customLookup) {
          scope.launch {
            draft.addCustom(
              modelId = modelId,
              lookup = { id -> accounts.modelDefaults(listOf(id), draft.api).firstOrNull() ?: ModelConfig(id) },
              fallbackError = failed,
            )
          }
        }
      }
    }
    modelRows(draft, models, shown)
    item { PillButton(label = stringResource(R.string.models_done), onClick = onBack, modifier = SpacedItem) }
  }
}

/** A settings page item [SettingsItemWidth] wide, set apart from the one above. */
private val SpacedItem = SettingsItemWidth.padding(top = PipkinTokens.Spacing.s12)

/** The models matching the search as one settings card, a lazy item per row, or why there are none to show. */
private fun LazyListScope.modelRows(draft: ModelDraft, models: List<ModelConfig>, shown: List<ModelConfig>) {
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

    models.isEmpty() -> item { SettingsCaption(text = stringResource(R.string.models_none), modifier = SpacedItem) }

    shown.isEmpty() -> item {
      SettingsCaption(text = stringResource(R.string.chat_model_search_empty), modifier = SpacedItem)
    }

    else -> itemsIndexed(shown) { index, config ->
      val radius = PipkinTokens.Radius.xl
      val top = if (index == 0) radius else 0.dp
      val bottom = if (index == shown.lastIndex) radius else 0.dp
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
}

/** Case-insensitive match on the model's id or name. */
private fun matches(model: ModelConfig, term: String): Boolean = term.isEmpty() ||
  model.id.contains(term, ignoreCase = true) || model.name?.contains(term, ignoreCase = true) == true

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

/** One quiet line offering to add [modelId], searched for but not listed, as a custom model, and why that failed. */
@Composable
private fun AddCustomModelRow(modelId: String, lookup: CustomModelLookup, onAdd: () -> Unit) {
  val colors = pipkinColors
  Column(modifier = SettingsItemWidth.padding(top = PipkinTokens.Spacing.s8)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 40.dp)
        .clip(RoundedCornerShape(PipkinTokens.Radius.xl))
        .clickable(enabled = lookup.addingId == null, onClick = onAdd)
        .padding(horizontal = PipkinTokens.Spacing.s16),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
    ) {
      if (lookup.addingId == modelId) {
        SimmeringPot()
      } else {
        LucideIcon(icon = R.drawable.ic_lucide_plus, contentDescription = null, tint = colors.accent)
      }
      Text(
        text = stringResource(R.string.models_add_custom, modelId),
        modifier = Modifier.weight(1f),
        color = colors.accent,
        fontSize = PipkinTokens.FontSize.base,
      )
    }
    lookup.errorFor(modelId)?.let { SettingsCaption(text = it, color = colors.destructive) }
  }
}
