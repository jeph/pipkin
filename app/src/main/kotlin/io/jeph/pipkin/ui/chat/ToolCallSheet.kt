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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.STREAM_UPDATE_MILLIS
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.agent.ToolStatus
import io.jeph.pipkin.agent.parsePartialJsonObject
import io.jeph.pipkin.ui.components.CodeBox
import io.jeph.pipkin.ui.components.CodeText
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinMarkdown
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.fileLanguage
import io.jeph.pipkin.ui.components.rememberHighlightedCode
import io.jeph.pipkin.ui.components.splitLines
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Details of a thinking block or tool call; [item] is a [TimelineItem.Thinking] or [TimelineItem.ToolCall].
 * Opened while it still streams, the sheet follows its end like the chat does until the user drags away.
 * [streamingArguments] gives a tool call's raw arguments while they stream, as the timeline's `streamingArguments`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCallSheet(item: TimelineItem, streamingArguments: (key: String) -> String?, onDismiss: () -> Unit) {
  val presentation = when (item) {
    is TimelineItem.ToolCall -> toolPresentation(item)
    is TimelineItem.Thinking -> toolPresentation(item)
    else -> return
  }
  val scrollState = rememberScrollState()
  // A finished item only grows while its first layout lands, which must not scroll it to the end.
  val streamingAtOpen = remember { presentation.running }
  val follow = remember { mutableStateOf(streamingAtOpen) }
  if (streamingAtOpen) AutoFollow(scrollState, follow)
  // The body already shows a thinking block's text, a command and other tools' arguments; only the file tools'
  // bodies leave out the path, so only they keep the row's summary under the title.
  val subtitle = presentation.summary.takeIf { item is TimelineItem.ToolCall && item.toolName in FILE_TOOLS }
  val copyText = when (item) {
    is TimelineItem.Thinking -> item.text
    is TimelineItem.ToolCall -> remember(item) { item.cardText() }
  }.takeUnless { presentation.running || it.isBlank() }
  PipkinSheet(onDismissRequest = onDismiss) {
    SheetTitle(presentation, subtitle, copyText)
    SelectionContainer {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .pinEnd(scrollState, follow)
            .verticalScroll(scrollState)
            .padding(horizontal = PipkinTokens.Spacing.s16),
        verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
      ) {
        when (item) {
          is TimelineItem.Thinking -> PipkinMarkdown(item.text)
          is TimelineItem.ToolCall -> ToolDetails(withStreamedArguments(item, streamingArguments))
        }
      }
    }
  }
}

/**
 * [item] with the arguments that have streamed in so far. The timeline only parses what the chat row shows, so
 * while the sheet is open it parses the raw text itself, off the main thread and once per [STREAM_UPDATE_MILLIS].
 */
@Composable
private fun withStreamedArguments(
  item: TimelineItem.ToolCall,
  streamingArguments: (key: String) -> String?,
): TimelineItem.ToolCall {
  if (!item.argsStreaming) return item
  val streamed by produceState<JSONObject?>(null, item.key) {
    withContext(Dispatchers.Default) {
      while (true) {
        val text = streamingArguments(item.key) ?: break
        parsePartialJsonObject(text)?.let { value = it }
        delay(STREAM_UPDATE_MILLIS)
      }
    }
  }
  return streamed?.let { item.copy(args = it) } ?: item
}

/** The sheet's header; once the item finished, [copyText] backs a copy button at its end. */
@Composable
private fun SheetTitle(presentation: ToolPresentation, subtitle: String?, copyText: String?) {
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
      SimmeringPot()
    } else {
      LucideIcon(
        icon = presentation.icon,
        contentDescription = null,
        tint = if (presentation.isError) colors.destructive else colors.foreground,
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(text = presentation.title, color = colors.foreground, fontSize = PipkinTokens.FontSize.lg)
      subtitle?.let {
        Text(
          text = it,
          color = colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.sm,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    copyText?.let {
      CopyButton(
        text = it,
        contentDescription = stringResource(R.string.chat_copy_details),
        tint = colors.foregroundMuted,
        iconSize = PipkinTokens.IconSize.md,
      )
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
    SimmeringPot(size = PipkinTokens.IconSize.sm)
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
 * Removed lines on a destructive tint, added lines on a success tint, one block per replacement. Each side
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
        added.splitLines().forEach { DiffLine("+ ", it, colors.dotSuccess.copy(alpha = 0.16f)) }
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

/** The sheet's body for tool call [this] as plain text, sections separated by blank lines; the header stays out. */
internal fun TimelineItem.ToolCall.cardText(): String {
  val sections = when (toolName) {
    // The sheet shows the output right under the command, in the same box.
    "bash" -> listOf(listOfNotNull("$ " + args?.stringOrNull("command").orEmpty(), output).joinToString("\n"))

    "edit" -> editPairs(args).map { (oldText, newText) ->
      (oldText.lines().map { "- $it" } + newText.lines().map { "+ $it" }).joinToString("\n")
    } + output

    "write" -> listOf(args?.stringOrNull("content"), output)

    "read" -> listOf(output)

    else -> listOf(args?.takeIf { it.length() > 0 }?.toString(2), output)
  }
  return sections.filterNot { it.isNullOrEmpty() }.joinToString("\n\n")
}

private val FILE_TOOLS = setOf("read", "write", "edit")
