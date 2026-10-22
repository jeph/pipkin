package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.StepsOutcome
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.text.NumberFormat

/**
 * A run of steps folded into one line: Working while the run works, Done once it's done or Stopped, with how many steps
 * and how many failed. Tapping it lists the steps under it, or folds them again. Folded while the run works, the step
 * running now shows under the line, so the chat doesn't grow with every step.
 */
@Composable
internal fun StepFoldRow(
  fold: TimelineItem.Steps,
  expanded: Boolean,
  onToggle: () -> Unit,
  onOpenTool: (key: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    StepFoldLine(fold, expanded, onToggle)
    if (!expanded && fold.outcome == StepsOutcome.Working) {
      val current = fold.current
      ToolRow(stepPresentation(current), onClick = { onOpenTool(current.key) })
    }
  }
}

@Composable
private fun StepFoldLine(fold: TimelineItem.Steps, expanded: Boolean, onToggle: () -> Unit) {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 30.dp)
      .clip(RoundedCornerShape(PipkinTokens.Radius.md))
      .clickable(
        onClickLabel = stringResource(if (expanded) R.string.chat_steps_collapse else R.string.chat_steps_expand),
        onClick = onToggle,
      ).padding(horizontal = PipkinTokens.Spacing.s4),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    OutcomeIcon(fold.outcome, failed = fold.failed > 0)
    FoldSummary(fold, Modifier.weight(1f))
    LucideIcon(
      icon = if (expanded) R.drawable.ic_lucide_chevron_up else R.drawable.ic_lucide_chevron_down,
      contentDescription = null,
      size = PipkinTokens.IconSize.sm,
      tint = colors.foregroundMuted,
    )
  }
}

/** The fold's state, then its counts. They sit together, so only the separator's own spaces part them. */
@Composable
private fun FoldSummary(fold: TimelineItem.Steps, modifier: Modifier) {
  val colors = pipkinColors
  val numbers = NumberFormat.getIntegerInstance()
  val separator = stringResource(R.string.separator_dot)
  Row(modifier = modifier) {
    Text(
      text = stringResource(
        when (fold.outcome) {
          StepsOutcome.Working -> R.string.chat_steps_working
          StepsOutcome.Done -> R.string.chat_steps_done
          StepsOutcome.Stopped -> R.string.chat_steps_stopped
        },
      ),
      color = colors.foreground,
      fontSize = 12.5.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
    )
    val count = fold.steps.size
    Text(
      text = separator + pluralStringResource(R.plurals.chat_steps_count, count, numbers.format(count)),
      modifier = Modifier.weight(1f, fill = false),
      color = colors.foregroundMuted,
      fontSize = 12.5.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (fold.failed > 0) {
      Text(
        text = separator + pluralStringResource(R.plurals.chat_steps_failed, fold.failed, numbers.format(fold.failed)),
        color = colors.destructive,
        fontSize = 12.5.sp,
        maxLines = 1,
      )
    }
  }
}

/**
 * A check once the run is done, or an alert in red when some of its steps [failed]; a square once it was stopped, or
 * a simmering pot while it works. As big as a step's icon, so it centres over the steps' icons.
 */
@Composable
private fun OutcomeIcon(outcome: StepsOutcome, failed: Boolean) {
  val colors = pipkinColors
  when (outcome) {
    StepsOutcome.Working -> SimmeringPot(size = STEP_ICON_SIZE, tint = colors.accent)

    StepsOutcome.Done -> LucideIcon(
      icon = if (failed) R.drawable.ic_lucide_circle_alert else R.drawable.ic_lucide_check,
      contentDescription = null,
      size = STEP_ICON_SIZE,
      tint = if (failed) colors.destructive else colors.dotSuccess,
    )

    StepsOutcome.Stopped -> LucideIcon(
      icon = R.drawable.ic_lucide_square,
      contentDescription = null,
      size = STEP_ICON_SIZE,
      tint = colors.foregroundMuted,
    )
  }
}

/** A step's row or thinking block's presentation, for the fold's step running now. */
@Composable
private fun stepPresentation(step: TimelineItem): ToolPresentation = when (step) {
  is TimelineItem.ToolCall -> toolPresentation(step)
  is TimelineItem.Thinking -> toolPresentation(step)
  else -> error("A fold holds only steps")
}

/**
 * Draws the rail that joins an expanded fold's steps through their icons, from the fold's line down, ending at the
 * [last] step's icon.
 */
internal fun Modifier.foldRail(last: Boolean, color: Color): Modifier = drawBehind {
  val inset = PipkinTokens.Spacing.s4.toPx() + STEP_ICON_SIZE.toPx() / 2
  val x = if (layoutDirection == LayoutDirection.Rtl) size.width - inset else inset
  val gap = RAIL_GAP.toPx()
  val middle = size.height / 2
  val stroke = RAIL_WIDTH.toPx()
  drawLine(color, Offset(x, 0f), Offset(x, middle - gap), stroke)
  if (!last) drawLine(color, Offset(x, middle + gap), Offset(x, size.height), stroke)
}

private val RAIL_WIDTH = 1.5.dp

/** How far the rail stops short of an icon's centre, leaving a little space around the icon. */
private val RAIL_GAP = 10.dp
