package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.TimelineState
import io.jeph.pipkin.ui.components.CapsLabel
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Long enough to read several queued messages, short enough to leave the stream most of the screen. */
private const val STEERING_MAX_LINES = 6

private const val DISABLED_ALPHA = 0.5f

private val BOX_SHAPE = RoundedCornerShape(20.dp)
private val BUTTON_HEIGHT = 30.dp
private val LINE_SIZE = 13.5.sp
private val LINE_HEIGHT = 19.5.sp

/**
 * The messages Pi queued, between the dialog card and the composer, while there are any. Edit and Send now are off
 * while Pi may be taking the queue for its next turn, since it then delivers the messages regardless.
 */
@Composable
internal fun PendingSteeringBox(controller: ChatController, timeline: TimelineState, compact: Boolean) {
  if (timeline.queue.isEmpty()) return
  SteeringBox(
    summary = controller.steering.summary(timeline.queue),
    compact = compact,
    modifier = Modifier.padding(bottom = PipkinTokens.Spacing.s8),
  ) {
    SteerButton(stringResource(R.string.chat_steering_edit), !timeline.queueInFlight, filled = false) {
      controller.steering.edit()
    }
    val sendNowEnabled = !timeline.queueInFlight && !controller.sending
    SteerButton(stringResource(R.string.chat_steering_send_now), sendNowEnabled, filled = true) {
      controller.steering.sendNow()
    }
  }
}

/**
 * The messages Pi has queued but not taken yet, one per line as typed, under a cobalt "Steering" label, so the user
 * sees what is about to steer the run; attachments show only as a count. [actions] end the label's line: the chat's
 * Edit takes them back into the composer and Send now stops the run and sends them at once, each as its own message;
 * a subagent's box has only Edit. A [compact] box leaves room for the keyboard or a dialog: one line with the first
 * message and how many lines follow it.
 */
@Composable
internal fun SteeringBox(
  summary: SteeringSummary,
  compact: Boolean,
  modifier: Modifier = Modifier,
  actions: @Composable RowScope.() -> Unit = {},
) {
  val colors = pipkinColors
  Box(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp), Alignment.TopCenter) {
    Column(
      modifier =
        Modifier
          .widthIn(max = PipkinTokens.contentMaxWidth)
          .fillMaxWidth()
          .clip(BOX_SHAPE)
          .background(colors.surface1)
          .padding(
            start = PipkinTokens.Spacing.s12,
            top = PipkinTokens.Spacing.s4,
            end = PipkinTokens.Spacing.s6,
            bottom = if (compact) PipkinTokens.Spacing.s4 else 10.dp,
          ),
    ) {
      // As tall as the buttons, so a box with none keeps its height.
      Row(modifier = Modifier.heightIn(min = BUTTON_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        if (compact) {
          SteeringLine(summary, Modifier.weight(1f))
        } else {
          CapsLabel(stringResource(R.string.chat_steering), colors.accent, Modifier.weight(1f), fontSize = 11.sp)
        }
        actions()
      }
      if (compact) return@Column
      if (summary.text.isNotEmpty()) {
        Text(
          text = summary.text,
          modifier = Modifier.padding(top = PipkinTokens.Spacing.s2, end = PipkinTokens.Spacing.s6),
          color = colors.foreground,
          fontSize = LINE_SIZE,
          lineHeight = LINE_HEIGHT,
          maxLines = STEERING_MAX_LINES,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (summary.attachments > 0) SteeringAttachments(summary.attachments)
    }
  }
}

/** A steering action: cobalt text, on a cobalt-tinted pill when [filled]. */
@Composable
internal fun SteerButton(label: String, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
  val colors = pipkinColors
  Text(
    text = label,
    modifier = Modifier
      .padding(start = PipkinTokens.Spacing.s4)
      .alpha(if (enabled) 1f else DISABLED_ALPHA)
      .heightIn(min = BUTTON_HEIGHT)
      .clip(CircleShape)
      .background(if (filled) colors.accentSoft else Color.Transparent)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = if (filled) PipkinTokens.Spacing.s12 else 10.dp, vertical = 7.dp),
    color = colors.accent,
    fontSize = 12.5.sp,
    fontWeight = FontWeight.Bold,
    maxLines = 1,
  )
}

/** The compact steering box's text: the first queued line, or the attachment count when there is no text, and "+N". */
@Composable
private fun SteeringLine(summary: SteeringSummary, modifier: Modifier) {
  val colors = pipkinColors
  val lines = summary.text.lines().filter { it.isNotBlank() }
  val first = lines.firstOrNull()
    ?: pluralStringResource(R.plurals.chat_steering_attachments, summary.attachments, summary.attachments)
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Text(
      text = first,
      modifier = Modifier.weight(1f, fill = false),
      color = colors.foreground,
      fontSize = LINE_SIZE,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (lines.size > 1) {
      Text(
        text = stringResource(R.string.chat_steering_more, lines.size - 1),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
private fun SteeringAttachments(count: Int) {
  val colors = pipkinColors
  Row(
    modifier = Modifier.padding(top = PipkinTokens.Spacing.s4),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    LucideIcon(
      R.drawable.ic_lucide_paperclip,
      contentDescription = null,
      size = PipkinTokens.IconSize.xs,
      tint = colors.foregroundMuted,
    )
    Text(
      text = pluralStringResource(R.plurals.chat_steering_attachments, count, count),
      color = colors.foregroundMuted,
      fontSize = LINE_SIZE,
    )
  }
}
