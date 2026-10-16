package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.TimelineState
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Long enough to read several queued messages, short enough to leave the stream most of the screen. */
private const val STEERING_MAX_LINES = 6

/** The messages Pi queued, between the dialog card and the composer, while there are any. */
@Composable
internal fun PendingSteeringBox(controller: ChatController, timeline: TimelineState, compact: Boolean) {
  if (timeline.queue.isEmpty()) return
  SteeringBox(
    summary = controller.steering.summary(timeline.queue),
    compact = compact,
    editEnabled = !timeline.queueInFlight,
    sendNowEnabled = !timeline.queueInFlight && !controller.sending,
    onEdit = controller.steering::edit,
    onSendNow = controller.steering::sendNow,
    modifier = Modifier.padding(bottom = PipkinTokens.Spacing.s8),
  )
}

/**
 * The messages Pi has queued but not taken yet, one per line as typed, so the user sees what is about to steer the
 * run; attachments show only as a count. Edit takes them back into the composer; Send now stops the run and sends
 * them at once, each as its own message. Both are off while Pi may be taking the queue for its next turn, since it
 * then delivers the messages regardless. A [compact] box leaves room for the keyboard or a dialog: one line with the
 * first message and how many lines follow it.
 */
@Composable
internal fun SteeringBox(
  summary: SteeringSummary,
  compact: Boolean,
  editEnabled: Boolean,
  sendNowEnabled: Boolean,
  onEdit: () -> Unit,
  onSendNow: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = pipkinColors
  Box(modifier = modifier.fillMaxWidth().padding(horizontal = PipkinTokens.Spacing.s16), Alignment.TopCenter) {
    Column(
      modifier =
        Modifier
          .widthIn(max = PipkinTokens.contentMaxWidth)
          .fillMaxWidth()
          .clip(RoundedCornerShape(PipkinTokens.Radius.lg))
          .background(colors.surface2)
          .padding(
            start = PipkinTokens.Spacing.s12,
            end = PipkinTokens.Spacing.s4,
            bottom = if (compact) 0.dp else PipkinTokens.Spacing.s12,
          ),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (compact) {
          SteeringLine(summary, Modifier.weight(1f))
        } else {
          Text(
            text = stringResource(R.string.chat_steering),
            modifier = Modifier.weight(1f),
            color = colors.foregroundMuted,
            fontSize = PipkinTokens.FontSize.sm,
          )
        }
        TextButton(onClick = onEdit, enabled = editEnabled) { Text(text = stringResource(R.string.chat_steering_edit)) }
        TextButton(onClick = onSendNow, enabled = sendNowEnabled) {
          Text(text = stringResource(R.string.chat_steering_send_now))
        }
      }
      if (compact) return@Column
      if (summary.text.isNotEmpty()) {
        Text(
          text = summary.text,
          modifier = Modifier.padding(end = PipkinTokens.Spacing.s8),
          color = colors.foreground,
          fontSize = PipkinTokens.FontSize.base,
          maxLines = STEERING_MAX_LINES,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (summary.attachments > 0) SteeringAttachments(summary.attachments)
    }
  }
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
      fontSize = PipkinTokens.FontSize.base,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (lines.size > 1) {
      Text(
        text = stringResource(R.string.chat_steering_more, lines.size - 1),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
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
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    LucideIcon(
      R.drawable.ic_lucide_paperclip,
      contentDescription = null,
      size = PipkinTokens.IconSize.sm,
      tint = colors.foregroundMuted,
    )
    Text(
      text = pluralStringResource(R.plurals.chat_steering_attachments, count, count),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
    )
  }
}
