package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.components.Spinner
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import org.json.JSONObject

private enum class ModelSheetSection { Model, Thinking }

/** Paseo's browser shows 4.5 rows of 44dp, so a cut-off row signals it scrolls, plus a 4dp top inset. */
private val BrowserViewport = 44.dp * 4.5f + 4.dp

/** Model and thinking-level picker for the composer's model chip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(controller: ChatController, model: JSONObject?, thinkingLevel: String?, onDismiss: () -> Unit) {
  var expanded by remember { mutableStateOf<ModelSheetSection?>(null) }
  var models by remember { mutableStateOf<List<JSONObject>?>(null) }
  var levels by remember { mutableStateOf<List<String>>(emptyList()) }
  LaunchedEffect(controller) { models = controller.query { availableModels() }.orEmpty() }
  // Pi clamps the available levels to the model, so they reload whenever it changes.
  LaunchedEffect(controller, model) { levels = controller.query { availableThinkingLevels() }.orEmpty() }
  val toggle = { section: ModelSheetSection -> expanded = if (expanded == section) null else section }
  PipkinSheet(onDismissRequest = onDismiss) {
    SheetRow(
      label = stringResource(R.string.chat_model),
      value = modelLabel(model) ?: stringResource(R.string.chat_no_model_chip),
      onClick = { toggle(ModelSheetSection.Model) },
      trailing = { ExpandIcon() },
    )
    if (expanded == ModelSheetSection.Model) {
      ModelBrowser(models, model) { selected ->
        controller.setModel(selected.optString("provider"), selected.optString("id"))
        expanded = null
      }
    }
    if (levels.isNotEmpty()) {
      SheetRow(
        label = stringResource(R.string.chat_thinking),
        value = thinkingLevel?.let(::levelLabel),
        onClick = { toggle(ModelSheetSection.Thinking) },
        trailing = { ExpandIcon() },
      )
      if (expanded == ModelSheetSection.Thinking) {
        levels.forEach { level ->
          SheetRow(
            label = levelLabel(level),
            modifier = Modifier.padding(start = PipkinTokens.Spacing.s16),
            onClick = {
              controller.setThinkingLevel(level)
              expanded = null
            },
            trailing = { if (level == thinkingLevel) CheckIcon() },
          )
        }
      }
    }
  }
}

/** Models grouped by provider, each group under a muted provider label; the current model is checked. */
@Composable
private fun ModelBrowser(models: List<JSONObject>?, current: JSONObject?, onSelect: (JSONObject) -> Unit) {
  if (models == null) {
    Box(modifier = Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) { Spinner() }
    return
  }
  if (models.isEmpty()) {
    SheetRow(label = stringResource(R.string.chat_no_model), labelColor = pipkinColors.foregroundMuted)
    return
  }
  val groups = remember(models) { models.groupBy { it.optString("provider") }.toList() }
  LazyColumn(
    modifier = Modifier.fillMaxWidth().heightIn(max = BrowserViewport).padding(top = PipkinTokens.Spacing.s4),
  ) {
    groups.forEach { (provider, providerModels) ->
      item(key = "provider:$provider") { ProviderLabel(provider) }
      items(providerModels, key = { "model:$provider/${it.optString("id")}" }) { entry ->
        SheetRow(
          label = modelLabel(entry).orEmpty(),
          onClick = { onSelect(entry) },
          trailing = { if (isSameModel(entry, current)) CheckIcon() },
        )
      }
    }
  }
}

@Composable
private fun ProviderLabel(provider: String) {
  Column(modifier = Modifier.fillMaxWidth().padding(PipkinTokens.Spacing.s16, PipkinTokens.Spacing.s8)) {
    Text(text = provider, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
  }
}

@Composable
private fun ExpandIcon() {
  LucideIcon(
    icon = R.drawable.ic_lucide_chevron_down,
    contentDescription = null,
    size = PipkinTokens.IconSize.sm,
    tint = pipkinColors.foregroundMuted,
  )
}

@Composable
private fun CheckIcon() {
  LucideIcon(icon = R.drawable.ic_lucide_check, contentDescription = null, tint = pipkinColors.accent)
}

/** Pi Model JSON's display name, falling back to its id; null without a model. */
internal fun modelLabel(model: JSONObject?): String? =
  model?.let { (it.opt("name") as? String)?.takeIf(String::isNotBlank) ?: it.optString("id") }

/** True when the model's `input` includes `image`. */
internal fun acceptsImages(model: JSONObject?): Boolean {
  val input = model?.optJSONArray("input") ?: return false
  return (0 until input.length()).any { input.optString(it) == "image" }
}

internal fun levelLabel(level: String): String = level.replaceFirstChar { it.uppercaseChar() }

private fun isSameModel(model: JSONObject, current: JSONObject?): Boolean = current != null &&
  model.optString("provider") == current.optString("provider") &&
  model.optString("id") == current.optString("id")
