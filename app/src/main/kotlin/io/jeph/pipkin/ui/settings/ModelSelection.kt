package io.jeph.pipkin.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.HeaderButton
import io.jeph.pipkin.ui.theme.pipkinColors

/** The models picked on a provider page, by long-pressing one and then tapping others, for removing together. */
@Stable
internal class ModelSelection(ids: Set<String>) {
  var ids by mutableStateOf(ids)
    private set

  val active: Boolean get() = ids.isNotEmpty()

  fun select(modelId: String) {
    ids += modelId
  }

  fun toggle(modelId: String) {
    ids = if (modelId in ids) ids - modelId else ids + modelId
  }

  fun selectAll(modelIds: List<String>) {
    ids += modelIds
  }

  fun clear() {
    ids = emptySet()
  }

  /** Drops selected models that left the page. */
  fun keep(modelIds: Set<String>) {
    if (ids.any { it !in modelIds }) ids = ids intersect modelIds
  }
}

/** A [ModelSelection] that survives configuration changes. */
@Composable
internal fun rememberModelSelection(): ModelSelection =
  rememberSaveable(saver = listSaver(save = { it.ids.toList() }, restore = { ModelSelection(it.toSet()) })) {
    ModelSelection(emptySet())
  }

/**
 * The provider page's header while models are selected: clear the selection (Back does too), select every model, or
 * take the selected ones off [draft] once confirmed, so a stray tap can't drop a whole list. Its actions are icons, so
 * the count keeps the room at large font sizes and in long translations.
 */
@Composable
internal fun ModelSelectionBar(selection: ModelSelection, draft: ModelDraft) {
  var confirmRemove by remember { mutableStateOf(false) }
  BackHandler(onBack = selection::clear)
  val count = selection.ids.size
  HeaderBar(
    title = pluralStringResource(R.plurals.provider_models_selected, count, count),
    leading = {
      HeaderButton(
        icon = R.drawable.ic_lucide_x,
        contentDescription = stringResource(R.string.provider_models_clear_selection),
        onClick = selection::clear,
      )
    },
    trailing = {
      HeaderButton(
        icon = R.drawable.ic_lucide_list_checks,
        contentDescription = stringResource(R.string.provider_models_select_all),
        onClick = { selection.selectAll(draft.added.map { it.id }) },
      )
      HeaderButton(
        icon = R.drawable.ic_lucide_trash_2,
        contentDescription = stringResource(R.string.provider_models_remove_selected),
        onClick = { confirmRemove = true },
        tint = pipkinColors.destructive,
      )
    },
  )
  if (!confirmRemove) return
  ConfirmDialog(
    title = pluralStringResource(R.plurals.provider_models_remove_title, count, count),
    text = pluralStringResource(R.plurals.provider_models_remove_text, count),
    confirmLabel = stringResource(R.string.provider_models_remove),
    onConfirm = {
      confirmRemove = false
      selection.ids.forEach(draft::remove)
      selection.clear()
    },
    onDismiss = { confirmRemove = false },
    icon = R.drawable.ic_lucide_trash_2,
    destructive = true,
  )
}
