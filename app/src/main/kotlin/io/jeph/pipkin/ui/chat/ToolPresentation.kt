package io.jeph.pipkin.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.agent.ToolStatus
import org.json.JSONObject

/** How a thinking block or tool call is labelled in its compact row and its detail sheet; [code] summaries are monospace. */
internal data class ToolPresentation(
  @DrawableRes val icon: Int,
  val title: String,
  val summary: String?,
  val running: Boolean,
  val isError: Boolean,
  val code: Boolean = true,
)

@Composable
internal fun toolPresentation(item: TimelineItem.ToolCall): ToolPresentation {
  val args = item.args
  val running = item.status != ToolStatus.Done
  val path = args?.stringOrNull("path")
  return when (item.toolName) {
    "bash" -> ToolPresentation(
      R.drawable.ic_lucide_terminal,
      stringResource(R.string.chat_tool_shell),
      args?.stringOrNull("command")?.oneLine(),
      running,
      item.isError,
    )

    "read" -> ToolPresentation(
      R.drawable.ic_lucide_file_text,
      stringResource(R.string.chat_tool_read),
      path,
      running,
      item.isError,
    )

    "write" -> ToolPresentation(
      R.drawable.ic_lucide_file_pen,
      stringResource(R.string.chat_tool_write),
      path,
      running,
      item.isError,
    )

    "edit" -> ToolPresentation(
      R.drawable.ic_lucide_file_pen,
      stringResource(R.string.chat_tool_edit),
      path,
      running,
      item.isError,
    )

    else -> ToolPresentation(R.drawable.ic_lucide_wrench, item.toolName, path, running, item.isError)
  }
}

@Composable
internal fun toolPresentation(item: TimelineItem.Thinking): ToolPresentation = ToolPresentation(
  R.drawable.ic_lucide_brain,
  stringResource(R.string.chat_thinking),
  // Reasoning usually opens with a bold heading line; show it without the markdown markers.
  item.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.trim('*', ' '),
  item.streaming,
  isError = false,
  code = false,
)

/** Android's `optString` turns JSON null and non-strings into text; this only accepts real strings. */
internal fun JSONObject.stringOrNull(name: String): String? = opt(name) as? String

private fun String.oneLine(): String = replace('\n', ' ').trim()
