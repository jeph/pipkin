package io.jeph.pipkin.ui.chat

import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ImagePart
import io.jeph.pipkin.agent.NoticeKind
import io.jeph.pipkin.agent.StepFigure
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.agent.text
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.MarkdownCache
import io.jeph.pipkin.ui.components.PipkinMarkdown
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * A tool call's or thinking block's one-line row: the tool's icon, the bold title and a muted summary, in monospace for
 * a tool's arguments, then [trailing], such as a subagent's status. The icon is cobalt while the step runs, red once
 * it failed, and muted once it's done. [onClickLabel] says what a tap does, for TalkBack.
 */
@Composable
internal fun ToolRow(
  presentation: ToolPresentation,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onClickLabel: String? = null,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  val colors = pipkinColors
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = 30.dp)
        .clip(RoundedCornerShape(PipkinTokens.Radius.md))
        .clickable(onClickLabel = onClickLabel, onClick = onClick)
        .padding(horizontal = PipkinTokens.Spacing.s4),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    StepIcon(presentation.icon, running = presentation.running, failed = presentation.isError)
    Text(
      text = presentation.title,
      color = colors.foreground,
      fontSize = 12.5.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
    )
    Text(
      text = presentation.summary.orEmpty(),
      modifier = Modifier.weight(1f),
      color = if (presentation.isError) colors.destructive else colors.foregroundMuted,
      fontSize = if (presentation.code) 11.5.sp else 12.5.sp,
      fontFamily = if (presentation.code) PipkinFonts.mono else null,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    presentation.figure?.let { FigureText(it) }
    trailing()
  }
}

/** A step's figure: lines added and removed, or how a shell command ended. Never cut short; the summary gives way. */
@Composable
private fun FigureText(figure: StepFigure) {
  val colors = pipkinColors
  val numbers = NumberFormat.getIntegerInstance()
  val text = when (figure) {
    is StepFigure.Lines -> buildAnnotatedString {
      if (figure.added > 0) {
        withStyle(SpanStyle(color = colors.dotSuccess)) {
          append(stringResource(R.string.chat_figure_added, numbers.format(figure.added)))
        }
      }
      if (figure.added > 0 && figure.removed > 0) append(' ')
      if (figure.removed > 0) {
        withStyle(SpanStyle(color = colors.destructive)) {
          append(stringResource(R.string.chat_figure_removed, numbers.format(figure.removed)))
        }
      }
    }

    is StepFigure.Exit -> colored(
      stringResource(R.string.chat_figure_exit, numbers.format(figure.code)),
      colors.destructive,
    )

    StepFigure.TimedOut -> colored(stringResource(R.string.chat_figure_timed_out), colors.dotWarning)

    StepFigure.Stopped -> colored(stringResource(R.string.chat_figure_stopped), colors.foregroundMuted)
  }
  if (text.isEmpty()) return
  Text(text = text, fontSize = 11.5.sp, fontFamily = PipkinFonts.mono, maxLines = 1, softWrap = false)
}

private fun colored(text: String, color: Color) = AnnotatedString(text, SpanStyle(color = color))

/** A step's tool icon, coloured by its status. */
@Composable
private fun StepIcon(@DrawableRes icon: Int, running: Boolean, failed: Boolean) {
  val colors = pipkinColors
  LucideIcon(
    icon = icon,
    contentDescription = null,
    size = STEP_ICON_SIZE,
    tint = when {
      running -> colors.accent
      failed -> colors.destructive
      else -> colors.foregroundMuted
    },
  )
}

/** The size of a step's icon and of a fold's, so rows and the fold's line and rail line up. */
internal val STEP_ICON_SIZE = 14.dp

@Composable
internal fun ErrorRow(message: String, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = PipkinTokens.Spacing.s6),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    LucideIcon(
      icon = R.drawable.ic_lucide_circle_alert,
      contentDescription = null,
      modifier = Modifier.padding(top = 2.dp),
      tint = colors.destructive,
    )
    SelectionContainer(modifier = Modifier.weight(1f)) {
      Text(text = message, color = colors.destructive, fontSize = 13.5.sp, lineHeight = 19.5.sp)
    }
  }
}

/** A centred pill noting Pi's own work on the chat, such as retrying or compacting. */
@Composable
internal fun NoticeRow(kind: NoticeKind, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  Box(
    modifier = modifier.fillMaxWidth().padding(vertical = PipkinTokens.Spacing.s4),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier
        .heightIn(min = 27.dp)
        .clip(CircleShape)
        .background(colors.surface1)
        .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s4),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      LucideIcon(
        icon = kind.icon,
        contentDescription = null,
        size = PipkinTokens.IconSize.sm,
        tint = colors.foregroundMuted,
      )
      Text(
        text = stringResource(kind.text),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@get:DrawableRes
private val NoticeKind.icon: Int
  get() = when (this) {
    NoticeKind.Retrying -> R.drawable.ic_lucide_refresh_cw
    NoticeKind.Compacting -> R.drawable.ic_lucide_archive
    NoticeKind.Compacted -> R.drawable.ic_lucide_check
  }

/**
 * Live `m:ss` while the run works, then how long it took. While the run waits on [subagents] queued or running, it
 * says how many. While the chat waits on [backgroundTasks], the simmering pot stays, saying how many.
 */
@Composable
internal fun TurnFooterRow(
  item: TimelineItem.TurnFooter,
  backgroundTasks: Int,
  modifier: Modifier = Modifier,
  subagents: Int = 0,
) {
  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = 28.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    val duration = item.durationMillis
    if (duration != null) {
      FooterText(durationText(duration))
      if (backgroundTasks > 0) SimmeringPot(size = PipkinTokens.IconSize.sm)
    } else {
      SimmeringPot(size = PipkinTokens.IconSize.sm)
      RunningElapsed(item.startedAt)
      if (subagents > 0) {
        val count = NumberFormat.getIntegerInstance().format(subagents)
        FooterText(pluralStringResource(R.plurals.chat_waiting_on_subagents, subagents, count))
      }
    }
    if (backgroundTasks == 0) return@Row
    FooterText(pluralStringResource(R.plurals.chat_background_tasks_running, backgroundTasks, backgroundTasks))
  }
}

@Composable
private fun FooterText(text: String) {
  Text(
    text = text,
    color = pipkinColors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.sm,
    style = LocalTextStyle.current.copy(fontFeatureSettings = PipkinFonts.TABULAR),
  )
}

@Composable
private fun RunningElapsed(startedAt: Long) {
  val now by produceState(System.currentTimeMillis(), startedAt) {
    while (true) {
      value = System.currentTimeMillis()
      delay(1_000L - (value - startedAt).mod(1_000L))
    }
  }
  FooterText(elapsedText(now - startedAt))
}

/** A running time as `0:42`, or `75:03` past an hour, with the locale's digits. */
internal fun elapsedText(millis: Long): String {
  val seconds = (millis / 1_000L).coerceAtLeast(0)
  return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun durationText(millis: Long): String {
  val seconds = (millis / 1_000L).toInt()
  return if (seconds >= 60) {
    stringResource(R.string.chat_duration_minutes, seconds / 60, seconds % 60)
  } else {
    stringResource(R.string.chat_duration_seconds, seconds)
  }
}
