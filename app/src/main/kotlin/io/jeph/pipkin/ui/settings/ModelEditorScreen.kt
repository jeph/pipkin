package io.jeph.pipkin.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** One model's settings; edits go straight into the draft, which the provider page saves. */
@Composable
internal fun ModelEditorScreen(draft: ModelDraft, modelId: String, onBack: () -> Unit) {
  // Held once, so removing the model doesn't blank the page while it slides away.
  val model = remember(draft, modelId) { draft.model(modelId) }
  if (model == null) {
    LaunchedEffect(Unit) { onBack() }
    return
  }
  SettingsPage(title = modelId, onBack = onBack) {
    LabeledField(
      label = stringResource(R.string.model_name),
      value = model.name,
      onValueChange = { model.name = it },
      placeholder = modelId,
    )
    SwitchRow(label = stringResource(R.string.model_images), checked = model.images, onChange = { model.images = it })
    SwitchRow(
      label = stringResource(R.string.model_thinking),
      checked = model.reasoning,
      onChange = { model.reasoning = it },
    )
    if (model.reasoning) ThinkingLevels(model)
    LabeledField(
      label = stringResource(R.string.model_context),
      value = model.contextWindow,
      onValueChange = { model.contextWindow = it },
      keyboardType = KeyboardType.Number,
      error = stringResource(R.string.model_count_invalid).takeIf { model.contextWindowInvalid },
    )
    LabeledField(
      label = stringResource(R.string.model_max_tokens),
      value = model.maxTokens,
      onValueChange = { model.maxTokens = it },
      keyboardType = KeyboardType.Number,
      error = stringResource(R.string.model_count_invalid).takeIf { model.maxTokensInvalid },
    )
    CostFields(model)
    TextButton(
      onClick = {
        draft.remove(modelId)
        onBack()
      },
      modifier = Modifier.fillMaxWidth(),
      colors = ButtonDefaults.textButtonColors(contentColor = pipkinColors.destructive),
    ) {
      Text(stringResource(R.string.model_remove))
    }
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_done)) }
  }
}

@Composable
private fun ThinkingLevels(model: ModelForm) {
  Column {
    FieldLabel(stringResource(R.string.model_levels))
    ModelConfig.THINKING_LEVELS.forEach { level ->
      val enabled = model.levelEnabled(level)
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .toggleable(value = enabled, role = Role.Checkbox, onValueChange = { model.setLevel(level, it) }),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(checked = enabled, onCheckedChange = null)
        Text(
          text = stringResource(levelLabel(level)),
          modifier = Modifier.padding(start = PipkinTokens.Spacing.s8),
          color = pipkinColors.foreground,
          fontSize = PipkinTokens.FontSize.base,
        )
      }
    }
  }
}

private fun levelLabel(level: String): Int = when (level) {
  "off" -> R.string.model_level_off
  "minimal" -> R.string.model_level_minimal
  "low" -> R.string.model_level_low
  "medium" -> R.string.model_level_medium
  "high" -> R.string.model_level_high
  "xhigh" -> R.string.model_level_xhigh
  else -> R.string.model_level_max
}

@Composable
private fun CostFields(model: ModelForm) {
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    FieldLabel(stringResource(R.string.model_cost))
    Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
      PriceField(R.string.model_cost_input, model.costInput, Modifier.weight(1f)) { model.costInput = it }
      PriceField(R.string.model_cost_output, model.costOutput, Modifier.weight(1f)) { model.costOutput = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
      PriceField(R.string.model_cost_cache_read, model.costCacheRead, Modifier.weight(1f)) { model.costCacheRead = it }
      PriceField(R.string.model_cost_cache_write, model.costCacheWrite, Modifier.weight(1f)) {
        model.costCacheWrite = it
      }
    }
    if (model.costInvalid) {
      SettingsCaption(text = stringResource(R.string.model_cost_invalid), color = pipkinColors.destructive)
    } else {
      SettingsCaption(text = stringResource(R.string.model_cost_hint))
    }
  }
}

@Composable
private fun PriceField(@StringRes label: Int, value: String, modifier: Modifier, onValueChange: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    label = { Text(stringResource(label)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
    singleLine = true,
  )
}

@Composable
private fun LabeledField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String? = null,
  keyboardType: KeyboardType = KeyboardType.Text,
  error: String? = null,
) {
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    FieldLabel(label)
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.fillMaxWidth(),
      placeholder = placeholder?.let { { Text(it) } },
      supportingText = error?.let { { Text(it) } },
      isError = error != null,
      keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
      singleLine = true,
    )
  }
}
