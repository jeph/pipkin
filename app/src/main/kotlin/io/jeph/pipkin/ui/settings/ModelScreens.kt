package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import io.jeph.pipkin.R
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.ui.components.DialogConfirm
import io.jeph.pipkin.ui.components.PipkinDialog
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat

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
