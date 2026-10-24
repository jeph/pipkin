package io.jeph.pipkin.ui.chat

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.SUBAGENT_TOOL
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.ui.subagents.ChatSubagents
import io.jeph.pipkin.ui.subagents.SubagentPhase
import io.jeph.pipkin.ui.subagents.rowLabel
import io.jeph.pipkin.ui.subagents.runTime
import io.jeph.pipkin.ui.subagents.subagentFacts
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.pipkinColors

/**
 * What a stream knows of the subagents its `subagent` tool rows started, from [chat], and what tapping one does:
 * [onOpen] with the call's id. [taskKey] is the key of the user message a subagent's own transcript starts with, its
 * task, which shows as the task rather than as the user's message.
 */
@Immutable
internal class StreamSubagents(
  val chat: ChatSubagents,
  val onOpen: (toolCallId: String) -> Unit,
  val taskKey: String? = null,
) {
  companion object {
    /** A stream whose subagent rows lead nowhere, as a subagent's own transcript, which starts none. */
    val None = StreamSubagents(ChatSubagents.None, onOpen = {})
  }
}

/** Whether [item] is the agent's call starting a subagent, which shows as a [SubagentToolRow]. */
internal val TimelineItem.isSubagentCall: Boolean
  get() = this is TimelineItem.ToolCall && toolName == SUBAGENT_TOOL && !byUser

/**
 * The tool row of the agent's `subagent` [call]: the bot icon, the agent's name and the task's few words, then the
 * subagent's running time, or its status once it isn't running. Tapping it opens the subagent's screen.
 */
@Composable
internal fun SubagentToolRow(call: TimelineItem.ToolCall, subagents: StreamSubagents, modifier: Modifier = Modifier) {
  val facts = subagentFacts(call.id, call, subagents.chat, parentPath = null)
  val run = facts.run
  val status = if (facts.phase == SubagentPhase.Running && run != null) runTime(run) else null
  val statusText = status ?: stringResource(facts.phase.rowLabel ?: R.string.subagent_row_queued)
  val agent = facts.agent.ifEmpty { call.toolName }
  val description = stringResource(R.string.subagent_row_description, agent, facts.description, statusText)
  ToolRow(
    presentation = ToolPresentation(
      icon = R.drawable.ic_lucide_bot,
      title = agent,
      summary = facts.description,
      running = facts.phase.active,
      isError = facts.phase == SubagentPhase.Failed,
      code = false,
    ),
    onClick = { subagents.onOpen(call.id) },
    modifier = modifier.semantics { contentDescription = description },
    onClickLabel = stringResource(R.string.subagent_open),
  ) {
    Text(
      text = statusText,
      color = if (facts.phase == SubagentPhase.Asking) pipkinColors.dotWarning else pipkinColors.foregroundMuted,
      fontSize = 11.5.sp,
      fontFamily = PipkinFonts.mono,
      style = LocalTextStyle.current.copy(fontFeatureSettings = PipkinFonts.TABULAR),
      maxLines = 1,
      softWrap = false,
    )
  }
}
