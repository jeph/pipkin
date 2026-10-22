package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.unit.Dp
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
 * Removed lines on a destructive tint, added lines on a success tint, one block per replacement. Each side
 * is coloured for [language] as a whole, so strings and comments spanning lines keep their colour.
 */
@Composable
internal fun EditDiff(edits: List<Pair<String, String>>, language: String?) {
  if (edits.isEmpty()) return
  val colors = pipkinColors
  SheetCode(padding = 0.dp) {
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
 * `(oldText, newText)` pairs of Pi 1.3.0's edit tool arguments: `edits[]`, which some models send as a
 * JSON string or a single object, plus the legacy top-level `oldText`/`newText`.
 */
internal fun editPairs(args: JSONObject?): List<Pair<String, String>> {
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
