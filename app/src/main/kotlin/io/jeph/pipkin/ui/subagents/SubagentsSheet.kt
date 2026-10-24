package io.jeph.pipkin.ui.subagents

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.SubagentRun
import io.jeph.pipkin.agent.TimelineState
import io.jeph.pipkin.agent.askingSubagent
import io.jeph.pipkin.ui.components.CapsLabel
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.text.NumberFormat

private const val SHEET_MAX_HEIGHT = 0.9f
private val RING_SIZE = 14.dp

/**
 * [process]'s subagents as its [timeline] shows them now. Standing aside, Pipkin subagents lists no runs, so the chat
 * shows no Subagents tab and names no subagent as a question's asker, and its `subagent` calls are ordinary tool rows.
 */
@Composable
internal fun rememberChatSubagents(process: AgentProcess, timeline: TimelineState): ChatSubagents {
  val runs by process.subagentRuns.collectAsStateWithLifecycle()
  val busy by process.busy.collectAsStateWithLifecycle()
  val standingAside by process.subagentsStandingAside.collectAsStateWithLifecycle()
  val cutOff by process.subagentsCutOff.collectAsStateWithLifecycle()
  return remember(runs, timeline, busy, standingAside, cutOff) {
    val tool = if (standingAside) SubagentTool.Other else SubagentTool.Pipkin
    ChatSubagents(runs, askingSubagent(timeline, runs), busy, tool, cutOff)
  }
}

/**
 * The chat's subagents in a sheet that grows to most of the screen and then scrolls: a header with how many there
 * are, how many are running and queued and the tokens they used, then the runs grouped Waiting for you, Running and
 * Finished. A waiting run's line is the [question] the chat's card asks. Tapping a run opens it through [onOpen], with
 * its tool call's id; [onStopAll] stops every active one. Closes once the chat has no subagents left, as after the
 * user's next message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentsSheet(
  subagents: ChatSubagents,
  question: String?,
  onOpen: (toolCallId: String) -> Unit,
  onStopAll: () -> Unit,
  onDismiss: () -> Unit,
) {
  LaunchedEffect(subagents.runs.isEmpty()) { if (subagents.runs.isEmpty()) onDismiss() }
  val groups = subagents.groups()
  PipkinSheet(onDismissRequest = onDismiss, maxHeightFraction = SHEET_MAX_HEIGHT) {
    SheetHeader(subagents)
    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
      val row = @Composable { run: SubagentRun -> RunRow(run, subagents, question, onOpen) }
      group(R.string.subagents_group_waiting, groups.waiting, row, warning = true)
      group(R.string.subagents_group_running, groups.running, row)
      group(R.string.subagents_group_finished, groups.finished, row)
    }
    if (subagents.active > 0) {
      Box(modifier = Modifier.padding(PipkinTokens.Spacing.s16)) {
        StopButton(
          label = stringResource(R.string.subagents_stop_all),
          onClick = onStopAll,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

/**
 * The bot icon beside "Subagents", over how many runs there are, how many are running (asking the user included) and,
 * when any, queued, and the tokens they used.
 */
@Composable
private fun SheetHeader(subagents: ChatSubagents) {
  val colors = pipkinColors
  val numbers = remember { NumberFormat.getIntegerInstance() }
  val runs = subagents.runs.size
  val running = subagents.running
  val queued = subagents.queued
  val tokens = subagents.runs.sumOf { it.tokens }
  val summary = listOfNotNull(
    pluralStringResource(R.plurals.subagents_runs, runs, numbers.format(runs)),
    pluralStringResource(R.plurals.subagents_running, running, numbers.format(running)),
    if (queued > 0) pluralStringResource(R.plurals.subagents_queued, queued, numbers.format(queued)) else null,
    if (tokens > 0) tokensText(tokens) else null,
  ).joinToString(stringResource(R.string.separator_dot))
  Row(
    modifier = Modifier.padding(
      start = PipkinTokens.Spacing.s16,
      end = PipkinTokens.Spacing.s16,
      bottom = PipkinTokens.Spacing.s12,
    ),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    LucideIcon(
      icon = R.drawable.ic_lucide_bot,
      contentDescription = null,
      size = PipkinTokens.IconSize.md,
      tint = colors.accent,
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(R.string.subagents_title),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.lg,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      // Wraps under large fonts, as the tool call sheet's subtitle does.
      Text(
        text = summary,
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** A group's caps label and its [runs], each over a divider; nothing when it has none. */
private fun LazyListScope.group(
  @StringRes label: Int,
  runs: List<SubagentRun>,
  row: @Composable (SubagentRun) -> Unit,
  warning: Boolean = false,
) {
  if (runs.isEmpty()) return
  item(key = "group:$label") {
    val colors = pipkinColors
    CapsLabel(
      text = stringResource(label),
      color = if (warning) colors.dotWarning else colors.foregroundMuted,
      modifier = Modifier.padding(
        start = PipkinTokens.Spacing.s16,
        end = PipkinTokens.Spacing.s16,
        top = PipkinTokens.Spacing.s12,
        bottom = PipkinTokens.Spacing.s6,
      ),
    )
  }
  items(runs, key = { it.id }) { run ->
    Column {
      HorizontalDivider(color = pipkinColors.border)
      row(run)
    }
  }
}

/**
 * One run: its status icon, the agent and the task's words, its time, a second line with what it does now, why it
 * failed or what it used, and a chevron to its screen.
 */
@Composable
private fun RunRow(run: SubagentRun, subagents: ChatSubagents, question: String?, onOpen: (String) -> Unit) {
  val colors = pipkinColors
  val phase = subagents.phase(run)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .clickable(onClickLabel = stringResource(R.string.subagent_open)) { onOpen(run.toolCallId) }
      .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    PhaseIcon(phase, Modifier.padding(top = 3.dp))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      val separator = stringResource(R.string.separator_dot)
      Text(
        text = buildAnnotatedString {
          withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(run.agent) }
          if (run.description.isNotEmpty()) append(separator + run.description)
        },
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RunLine(run, phase, question)
    }
    Text(
      text = runTime(run).orEmpty(),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      style = LocalTextStyle.current.copy(fontFeatureSettings = PipkinFonts.TABULAR),
      maxLines = 1,
    )
    LucideIcon(
      icon = R.drawable.ic_lucide_chevron_right,
      contentDescription = null,
      size = PipkinTokens.IconSize.sm,
      tint = colors.foregroundMuted,
    )
  }
}

/**
 * A run's second line: the [question] it asks, what it does now and its turn, its phase while queued or stopped, why
 * it failed, or what it used once done.
 */
@Composable
private fun RunLine(run: SubagentRun, phase: SubagentPhase, question: String?) {
  val colors = pipkinColors
  val separator = stringResource(R.string.separator_dot)
  val numbers = remember { NumberFormat.getIntegerInstance() }
  val (text, color, code) = when (phase) {
    SubagentPhase.Asking ->
      Triple(question ?: stringResource(SubagentPhase.Asking.label), colors.dotWarning, false)

    SubagentPhase.Running -> {
      val turn = run.turn.takeIf { it > 0 }?.let { stringResource(R.string.subagent_turn, numbers.format(it)) }
      Triple(listOfNotNull(run.lastActivity, turn).joinToString(separator), colors.foregroundMuted, true)
    }

    SubagentPhase.Failed -> Triple(
      run.error?.let { stringResource(R.string.subagents_failed_because, it) } ?: stringResource(phase.label),
      colors.destructive,
      false,
    )

    SubagentPhase.Done -> Triple(
      if (run.tokens > 0) {
        stringResource(R.string.subagents_done_tokens, tokensText(run.tokens))
      } else {
        stringResource(phase.label)
      },
      colors.foregroundMuted,
      false,
    )

    SubagentPhase.Queued, SubagentPhase.Stopped, SubagentPhase.Interrupted ->
      Triple(stringResource(phase.label), colors.foregroundMuted, false)
  }
  if (text.isEmpty()) return
  Text(
    text = text,
    color = color,
    fontSize = if (code) 11.5.sp else PipkinTokens.FontSize.sm,
    fontFamily = if (code) PipkinFonts.mono else null,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/**
 * A run's status as an icon: a ring with a dot while it runs, cobalt, or amber while it asks; an empty ring while
 * queued; a check once done, a cross once failed, a square once stopped, and an alert once interrupted.
 */
@Composable
internal fun PhaseIcon(phase: SubagentPhase, modifier: Modifier = Modifier) {
  val color = phaseColor(phase)
  val icon = when (phase) {
    SubagentPhase.Done -> R.drawable.ic_lucide_check
    SubagentPhase.Failed -> R.drawable.ic_lucide_x
    SubagentPhase.Stopped -> R.drawable.ic_lucide_square
    SubagentPhase.Interrupted -> R.drawable.ic_lucide_circle_alert
    SubagentPhase.Queued, SubagentPhase.Running, SubagentPhase.Asking -> null
  }
  if (icon != null) {
    LucideIcon(icon = icon, contentDescription = null, modifier = modifier, size = RING_SIZE, tint = color)
    return
  }
  Box(
    modifier = modifier.size(RING_SIZE).border(1.5.dp, color, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    if (phase != SubagentPhase.Queued) RingDot(color)
  }
}

@Composable
private fun RingDot(color: Color) {
  Canvas(modifier = Modifier.size(6.dp)) { drawCircle(color) }
}

/** An outlined red button with a square, which stops subagents. */
@Composable
internal fun StopButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(20.dp)
  Row(
    modifier = modifier
      .heightIn(min = 40.dp)
      .clip(shape)
      .border(1.5.dp, colors.destructive, shape)
      .clickable(onClick = onClick)
      .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6, Alignment.CenterHorizontally),
  ) {
    LucideIcon(
      icon = R.drawable.ic_lucide_square,
      contentDescription = null,
      size = PipkinTokens.IconSize.sm,
      tint = colors.destructive,
    )
    Text(
      text = label,
      color = colors.destructive,
      fontSize = PipkinTokens.FontSize.base,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
    )
  }
}
