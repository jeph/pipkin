package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.components.Spinner
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import org.json.JSONObject

/** Both pickers wrap their content up to this share of the screen, then scroll inside. */
private const val PICKER_MAX_HEIGHT = 0.8f

/** The composer's model picker: a search field over the models grouped by provider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(controller: ChatController, model: JSONObject?, onDismiss: () -> Unit) {
  var models by remember { mutableStateOf<List<JSONObject>?>(null) }
  var query by rememberSaveable { mutableStateOf("") }
  val accounts = LocalContext.current.appGraph.accountRepository
  val status by accounts.status.collectAsStateWithLifecycle()
  val refreshError by accounts.refreshError.collectAsStateWithLifecycle()
  LaunchedEffect(controller) { models = controller.query { availableModels() }.orEmpty() }
  LaunchedEffect(accounts) { accounts.refresh() }
  // Pi's RPC lists every model with credentials; settings.json `enabledModels` picks the ones shown.
  val scoped = remember(models, status, refreshError) {
    val scope = status?.scope
    when {
      models == null || (status == null && refreshError == null) -> null
      scope == null -> models
      else -> models?.filter { "${it.optString("provider")}/${it.optString("id")}" in scope }
    }
  }
  PipkinSheet(onDismissRequest = onDismiss, maxHeightFraction = PICKER_MAX_HEIGHT) {
    SearchField(query, onQueryChange = { query = it })
    ModelList(scoped, query.trim(), model, Modifier.weight(1f, fill = false)) { selected ->
      controller.setModel(selected.optString("provider"), selected.optString("id"))
      onDismiss()
    }
  }
}

/** The composer's reasoning-effort picker: the levels Pi offers for the current model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThinkingSheet(
  controller: ChatController,
  model: JSONObject?,
  thinkingLevel: String?,
  onDismiss: () -> Unit,
) {
  var levels by remember { mutableStateOf<List<String>?>(null) }
  // Pi clamps the available levels to the model, so they reload whenever it changes.
  LaunchedEffect(controller, model) { levels = controller.query { availableThinkingLevels() }.orEmpty() }
  PipkinSheet(onDismissRequest = onDismiss, maxHeightFraction = PICKER_MAX_HEIGHT) {
    SheetTitle(stringResource(R.string.chat_reasoning))
    val shown = levels
    if (shown == null) {
      Loading()
      return@PipkinSheet
    }
    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
      shown.forEach { level ->
        SheetRow(
          // Pi's own level names, which are lowercase (`low`, `xhigh`).
          label = level,
          onClick = {
            controller.setThinkingLevel(level)
            onDismiss()
          },
          trailing = { if (level == thinkingLevel) CheckIcon() },
        )
      }
    }
  }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
  val colors = pipkinColors
  BasicTextField(
    value = query,
    onValueChange = onQueryChange,
    modifier = Modifier.fillMaxWidth().padding(
      horizontal = PipkinTokens.Spacing.s16,
      vertical = PipkinTokens.Spacing.s4,
    ),
    textStyle = TextStyle(color = colors.foreground, fontSize = PipkinTokens.FontSize.base),
    cursorBrush = SolidColor(colors.accent),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    singleLine = true,
    decorationBox = { field ->
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(colors.surface2, RoundedCornerShape(PipkinTokens.Radius.lg))
            .padding(horizontal = PipkinTokens.Spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
      ) {
        LucideIcon(icon = R.drawable.ic_lucide_search, contentDescription = null, tint = colors.foregroundMuted)
        Box(modifier = Modifier.weight(1f)) {
          if (query.isEmpty()) {
            Text(
              text = stringResource(R.string.chat_model_search),
              color = colors.foregroundMuted,
              fontSize = PipkinTokens.FontSize.base,
            )
          }
          field()
        }
      }
    },
  )
}

/** Models matching [query], grouped by provider under a muted label; the current model is checked. */
@Composable
private fun ModelList(
  models: List<JSONObject>?,
  query: String,
  current: JSONObject?,
  modifier: Modifier,
  onSelect: (JSONObject) -> Unit,
) {
  if (models == null) {
    Loading()
    return
  }
  val groups = remember(models, query) {
    models.filter { matches(it, query) }.groupBy { it.optString("provider") }.toList()
  }
  if (groups.isEmpty()) {
    val empty = if (models.isEmpty()) R.string.chat_no_model else R.string.chat_model_search_empty
    SheetRow(label = stringResource(empty), labelColor = pipkinColors.foregroundMuted)
    return
  }
  LazyColumn(modifier = modifier.fillMaxWidth().padding(top = PipkinTokens.Spacing.s4)) {
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

/** Case-insensitive match on the model's name, id or provider. */
private fun matches(model: JSONObject, query: String): Boolean = query.isEmpty() ||
  listOf(modelLabel(model).orEmpty(), model.optString("id"), model.optString("provider"))
    .any { it.contains(query, ignoreCase = true) }

@Composable
private fun SheetTitle(text: String) {
  Text(
    text = text,
    modifier = Modifier.fillMaxWidth().padding(
      horizontal = PipkinTokens.Spacing.s16,
      vertical = PipkinTokens.Spacing.s8,
    ),
    color = pipkinColors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.sm,
  )
}

@Composable
private fun Loading() {
  Box(modifier = Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) { Spinner() }
}

@Composable
private fun ProviderLabel(provider: String) {
  Column(modifier = Modifier.fillMaxWidth().padding(PipkinTokens.Spacing.s16, PipkinTokens.Spacing.s8)) {
    Text(text = provider, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
  }
}

@Composable
private fun CheckIcon() {
  LucideIcon(icon = R.drawable.ic_lucide_check, contentDescription = null, tint = pipkinColors.accent)
}
