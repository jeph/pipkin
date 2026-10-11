package io.jeph.pipkin.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

private const val SHEET_MAX_HEIGHT = 0.8f

/** `/session`: the session's name, file and id, message counts, tokens, context use and cost, as in Pi's terminal UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionInfoSheet(stats: JSONObject, name: String?, home: String?, onDismiss: () -> Unit) {
  val numbers = NumberFormat.getIntegerInstance()
  val count = { key: String -> numbers.format(stats.optLong(key)) }
  PipkinSheet(onDismissRequest = onDismiss, maxHeightFraction = SHEET_MAX_HEIGHT) {
    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
      SectionTitle(R.string.session_info)
      name?.let { InfoRow(R.string.session_name, it) }
      val file = stats.opt("sessionFile") as? String
      InfoRow(
        R.string.session_file,
        file?.let { path -> home?.let { tildePath(path, it) } ?: path } ?: stringResource(R.string.session_in_memory),
      )
      InfoRow(R.string.session_id, stats.optString("sessionId"))
      SectionTitle(R.string.session_messages)
      InfoRow(R.string.session_total, count("totalMessages"))
      InfoRow(R.string.session_user, count("userMessages"))
      InfoRow(R.string.session_assistant, count("assistantMessages"))
      InfoRow(R.string.session_tool_calls, count("toolCalls"))
      InfoRow(R.string.session_tool_results, count("toolResults"))
      stats.optJSONObject("tokens")?.let { TokenRows(it, numbers) }
      stats.optJSONObject("contextUsage")?.let { ContextRow(it, numbers) }
      val cost = stats.optDouble("cost", 0.0)
      if (cost > 0) {
        SectionTitle(R.string.session_cost)
        InfoRow(R.string.session_total, String.format(Locale.US, "$%.3f", cost))
      }
    }
  }
}

/** Input counts the whole prompt, cached or not, like Pi's terminal UI. */
@Composable
private fun TokenRows(tokens: JSONObject, numbers: NumberFormat) {
  val cacheRead = tokens.optLong("cacheRead")
  val input = tokens.optLong("input") + cacheRead + tokens.optLong("cacheWrite")
  SectionTitle(R.string.session_tokens)
  InfoRow(R.string.session_input, numbers.format(input))
  if (cacheRead > 0) InfoRow(R.string.session_cached, numbers.format(cacheRead))
  InfoRow(R.string.session_output, numbers.format(tokens.optLong("output")))
  InfoRow(R.string.session_total, numbers.format(tokens.optLong("total")))
}

/** Pi reports no context tokens right after compacting, until the next reply; then only the window shows. */
@Composable
private fun ContextRow(usage: JSONObject, numbers: NumberFormat) {
  val window = numbers.format(usage.optLong("contextWindow"))
  val value = if (usage.isNull("tokens")) {
    stringResource(R.string.session_context_unknown, window)
  } else {
    val percent = usage.optDouble("percent", 0.0).roundToInt()
    stringResource(R.string.session_context_value, numbers.format(usage.optLong("tokens")), window, percent)
  }
  InfoRow(R.string.session_context, value)
}

/** `/fork`: the user messages to fork from, newest first; picking one forks and fills the composer with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForkSheet(messages: List<JSONObject>, onDismiss: () -> Unit, onFork: (entryId: String) -> Unit) {
  val colors = pipkinColors
  PipkinSheet(onDismissRequest = onDismiss, maxHeightFraction = SHEET_MAX_HEIGHT) {
    SectionTitle(R.string.chat_fork_title)
    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
      messages.asReversed().forEach { message ->
        Text(
          text = message.optString("text").trim(),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable {
              onDismiss()
              onFork(message.optString("entryId"))
            }
            .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s12),
          color = colors.foreground,
          fontSize = PipkinTokens.FontSize.base,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
  Text(
    text = stringResource(text),
    modifier = Modifier.fillMaxWidth().padding(
      start = PipkinTokens.Spacing.s16,
      end = PipkinTokens.Spacing.s16,
      top = PipkinTokens.Spacing.s12,
      bottom = PipkinTokens.Spacing.s4,
    ),
    color = pipkinColors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.sm,
  )
}

/** A label with its value right-aligned; long values such as the session file wrap instead of being cut. */
@Composable
private fun InfoRow(@StringRes label: Int, value: String) {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 36.dp)
      .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s16),
  ) {
    Text(text = stringResource(label), color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.base)
    Text(
      text = value,
      modifier = Modifier.weight(1f),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
      textAlign = TextAlign.End,
    )
  }
}
