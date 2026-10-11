package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.agent.ToolStatus
import io.jeph.pipkin.ui.components.CodeBox
import io.jeph.pipkin.ui.components.CodeText
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinMarkdown
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.Spinner
import io.jeph.pipkin.ui.components.fileLanguage
import io.jeph.pipkin.ui.components.rememberHighlightedCode
import io.jeph.pipkin.ui.components.splitLines
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import org.json.JSONArray
import org.json.JSONObject

/** Details of a thinking block or tool call; [item] is a [TimelineItem.Thinking] or [TimelineItem.ToolCall]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCallSheet(item: TimelineItem, onDismiss: () -> Unit) {
  val presentation = when (item) {
    is TimelineItem.ToolCall -> toolPresentation(item)
    is TimelineItem.Thinking -> toolPresentation(item)
    else -> return
  }
  PipkinSheet(onDismissRequest = onDismiss) {
    SheetTitle(presentation)
    SelectionContainer {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PipkinTokens.Spacing.s16),
        verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
      ) {
        when (item) {
          is TimelineItem.Thinking -> PipkinMarkdown(item.text)
          is TimelineItem.ToolCall -> ToolDetails(item)
        }
      }
    }
  }
}

@Composable
private fun SheetTitle(presentation: ToolPresentation) {
  val colors = pipkinColors
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s16, bottom = PipkinTokens.Spacing.s12),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    if (presentation.running) {
      Spinner()
    } else {
      LucideIcon(
        icon = presentation.icon,
        contentDescription = null,
        tint = if (presentation.isError) colors.destructive else colors.foreground,
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(text = presentation.title, color = colors.foreground, fontSize = PipkinTokens.FontSize.lg)
      presentation.summary?.let {
        Text(
          text = it,
          color = colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.sm,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun ToolDetails(item: TimelineItem.ToolCall) {
  val args = item.args
  val language = args?.stringOrNull("path")?.let(::fileLanguage)
  when (item.toolName) {
    "bash" -> {
      CodeBox {
        val command = rememberHighlightedCode(args?.stringOrNull("command").orEmpty(), "sh")
        CodeText(AnnotatedString("$ ") + command)
        item.output?.takeIf { it.isNotEmpty() }?.let {
          CodeText(it, color = if (item.isError) pipkinColors.destructive else pipkinColors.foreground)
        }
      }
      if (item.output == null && item.status != ToolStatus.Done) RunningLine()
    }

    "edit" -> {
      EditDiff(editPairs(args), language)
      Output(item)
    }

    "write" -> {
      args?.stringOrNull("content")?.let { CodeBox { CodeText(it, language) } }
      Output(item)
    }

    "read" -> Output(item, codeBox = true, language = language)

    else -> {
      if (args != null && args.length() > 0) {
        Label(stringResource(R.string.chat_arguments))
        CodeBox { CodeText(args.toString(2), "json") }
      }
      Label(stringResource(R.string.chat_output))
      Output(item, codeBox = true)
    }
  }
}

/** The tool's result text, in a [CodeBox] coloured for [language] when [codeBox]; errors stay plain destructive. */
@Composable
private fun Output(item: TimelineItem.ToolCall, codeBox: Boolean = false, language: String? = null) {
  val colors = pipkinColors
  val output = item.output
  when {
    output == null -> if (item.status != ToolStatus.Done) RunningLine()
    item.isError -> Text(text = output, color = colors.destructive, fontSize = PipkinTokens.FontSize.base)
    !codeBox -> Text(text = output, color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.base)
    output.isEmpty() -> CodeBox { CodeText(stringResource(R.string.chat_no_output)) }
    else -> CodeBox { CodeText(output, language) }
  }
}

@Composable
private fun RunningLine() {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Spinner(size = PipkinTokens.IconSize.sm)
    Text(
      text = stringResource(R.string.chat_running),
      color = pipkinColors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.meta,
    )
  }
}

@Composable
private fun Label(text: String) {
  Text(text = text, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
}

/**
 * Removed lines on a destructive tint, added lines on an accent tint, one block per replacement. Each side
 * is coloured for [language] as a whole, so strings and comments spanning lines keep their colour.
 */
@Composable
private fun EditDiff(edits: List<Pair<String, String>>, language: String?) {
  if (edits.isEmpty()) return
  val colors = pipkinColors
  CodeBox(radius = PipkinTokens.Radius.base, padding = 0.dp) {
    Column(modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = PipkinTokens.Spacing.s8)) {
      edits.forEachIndexed { index, (oldText, newText) ->
        if (index > 0) {
          HorizontalDivider(
            modifier = Modifier.padding(vertical = PipkinTokens.Spacing.s8),
            thickness = 1.dp,
            color = colors.border,
          )
        }
        val removed = rememberHighlightedCode(oldText, language)
        val added = rememberHighlightedCode(newText, language)
        removed.splitLines().forEach { DiffLine("- ", it, colors.destructive.copy(alpha = 0.16f)) }
        added.splitLines().forEach { DiffLine("+ ", it, colors.accent.copy(alpha = 0.16f)) }
      }
    }
  }
}

@Composable
private fun DiffLine(marker: String, line: AnnotatedString, tint: Color) {
  Row(modifier = Modifier.fillMaxWidth().background(tint).padding(horizontal = PipkinTokens.Spacing.s12)) {
    CodeText(AnnotatedString(marker) + line)
  }
}

/**
 * `(oldText, newText)` pairs of Pi 1.1.0's edit tool arguments: `edits[]`, which some models send as a
 * JSON string or a single object, plus the legacy top-level `oldText`/`newText`.
 */
private fun editPairs(args: JSONObject?): List<Pair<String, String>> {
  if (args == null) return emptyList()
  val raw = args.opt("edits")
  // A string that is not a JSON array shows no diff; the tool result says what Pi made of it.
  val edits = raw as? JSONArray
    ?: (raw as? JSONObject)?.let { JSONArray().put(it) }
    ?: (raw as? String)?.let { runCatching { JSONArray(it) }.getOrNull() }
  val pairs = (0 until (edits?.length() ?: 0)).mapNotNull { index ->
    val edit = edits?.optJSONObject(index)
    edit?.stringOrNull("oldText")?.let { oldText -> oldText to edit.stringOrNull("newText").orEmpty() }
  }
  val legacyOld = args.stringOrNull("oldText") ?: return pairs
  return pairs + (legacyOld to args.stringOrNull("newText").orEmpty())
}
