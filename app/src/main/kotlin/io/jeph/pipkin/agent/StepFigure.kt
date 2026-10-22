package io.jeph.pipkin.agent

import org.json.JSONObject

/** What a tool call's row notes about its result, beside its arguments. */
sealed interface StepFigure {
  /** An edit or write changed the file by [added] and [removed] lines. */
  data class Lines(val added: Int, val removed: Int) : StepFigure

  /** A shell command exited with [code], other than 0. */
  data class Exit(val code: Int) : StepFigure

  /** A shell command ran past its timeout. */
  data object TimedOut : StepFigure

  /** A shell command was stopped. */
  data object Stopped : StepFigure
}

/** This call finished with [output], failed or not, and the `details` [result] carries, which set its figure. */
internal fun TimelineItem.ToolCall.withResult(output: String?, failed: Boolean, result: JSONObject?) = copy(
  output = output,
  isError = failed,
  status = ToolStatus.Done,
  figure = stepFigure(toolName, args, output, failed, result?.optJSONObject("details")),
)

/**
 * The figure for a [toolName] call with [args] whose result came: [output], whether it [failed], and the result's
 * [details]. Edits count the `+` and `-` lines of the diff Pi's edit tool returns, so an edit from before Pi returned
 * one, or one that failed, has none. A write counts the lines it wrote. A shell command reads its status from the last
 * line Pi appends to the output.
 */
internal fun stepFigure(
  toolName: String,
  args: JSONObject?,
  output: String?,
  failed: Boolean,
  details: JSONObject?,
): StepFigure? = when (toolName) {
  "edit" -> details?.optString("diff")?.takeUnless { failed || it.isEmpty() }?.let(::diffLines)
  "write" -> args?.optString("content")?.takeUnless { failed }?.let { StepFigure.Lines(lineCount(it), 0) }
  "bash" -> output?.let(::shellStatus)
  else -> null
}

/** The `+` and `-` lines of Pi's diff (`+12 text`, `-12 text`); context lines start with a space. */
private fun diffLines(diff: String): StepFigure.Lines {
  var added = 0
  var removed = 0
  for (line in diff.lineSequence()) {
    when (line.firstOrNull()) {
      '+' -> added++
      '-' -> removed++
    }
  }
  return StepFigure.Lines(added, removed)
}

/** How many lines [text] holds; a final line break ends the last line rather than starting another. */
private fun lineCount(text: String): Int {
  if (text.isEmpty()) return 0
  val breaks = text.count { it == '\n' }
  return if (text.endsWith('\n')) breaks else breaks + 1
}

/** The status Pi's shell tool appends as the output's last line, or null when the command exited with 0. */
private fun shellStatus(output: String): StepFigure? {
  val last = output.trimEnd().substringAfterLast('\n')
  if (last == "Command aborted") return StepFigure.Stopped
  if (TIMED_OUT.matches(last)) return StepFigure.TimedOut
  return EXITED.matchEntire(last)?.groupValues?.get(1)?.toIntOrNull()?.let(StepFigure::Exit)
}

private val EXITED = Regex("""Command exited with code (-?\d+)""")
private val TIMED_OUT = Regex("""Command timed out after \d+(\.\d+)? seconds""")
