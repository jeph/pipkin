package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import io.jeph.pipkin.R
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.ui.components.DialogConfirm
import io.jeph.pipkin.ui.components.PipkinDialog
import io.jeph.pipkin.ui.components.SelectionCircle
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat

/**
 * The provider page's models: the add-models button and one row per added model. Tapping a model opens it; a long
 * press starts [selection], during which tapping a model selects or deselects it instead.
 */
@Composable
internal fun ModelsSection(
  draft: ModelDraft,
  selection: ModelSelection,
  emptyText: String,
  onAddModels: () -> Unit,
  onOpenModel: (String) -> Unit,
) {
  val added = draft.added
  val addedIds = added.mapTo(HashSet()) { it.id }
  LaunchedEffect(addedIds) { selection.keep(addedIds) }
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      FieldLabel(text = stringResource(R.string.provider_models), modifier = Modifier.weight(1f))
      SmallButton(
        label = stringResource(R.string.provider_add_models),
        onClick = onAddModels,
        enabled = draft.canAddModels && !selection.active,
      )
    }
    if (added.isEmpty()) {
      SettingsCaption(text = emptyText)
      return@Column
    }
    SettingsSection(title = null) {
      added.forEachIndexed { index, model ->
        if (index > 0) SettingsDivider()
        ModelRow(model, selection, onOpenModel)
      }
    }
  }
}

/**
 * One added model; while models are selected, a check circle takes the chevron's place, so no row reflows, and tapping
 * selects or deselects it.
 */
@Composable
private fun ModelRow(model: ModelForm, selection: ModelSelection, onOpenModel: (String) -> Unit) {
  val preview = model.preview()
  val title = preview.name ?: preview.id
  val selecting = selection.active
  val selected = model.id in selection.ids
  val clickLabel = when {
    !selecting -> null
    selected -> stringResource(R.string.provider_models_deselect, title)
    else -> stringResource(R.string.provider_models_select, title)
  }
  SettingsRow(
    title = title,
    modifier = Modifier
      .then(if (selected) Modifier.background(pipkinColors.accentSoft) else Modifier)
      .combinedClickable(
        onClickLabel = clickLabel,
        onLongClick = { selection.select(model.id) },
        onClick = { if (selecting) selection.toggle(model.id) else onOpenModel(model.id) },
      ),
    subtitle = if (model.valid) modelSubtitle(preview) else stringResource(R.string.provider_model_invalid),
  ) {
    // One width in both modes, so the text beside it wraps the same way when selecting starts.
    Box(modifier = Modifier.size(PipkinTokens.IconSize.lg), contentAlignment = Alignment.Center) {
      if (selecting) SelectionCircle(selected) else Chevron()
    }
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
