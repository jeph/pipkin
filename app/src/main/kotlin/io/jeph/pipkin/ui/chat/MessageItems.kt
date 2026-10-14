package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ImagePart
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.MarkdownCache
import io.jeph.pipkin.ui.components.PipkinMarkdown
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val UserBubbleShape =
  RoundedCornerShape(
    topStart = PipkinTokens.Radius.xxl,
    topEnd = PipkinTokens.Radius.sm,
    bottomEnd = PipkinTokens.Radius.xxl,
    bottomStart = PipkinTokens.Radius.xxl,
  )
private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val ImageMaxEdge = 160.dp

@Composable
internal fun UserMessage(item: TimelineItem.User, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
    Column(
      modifier = Modifier.background(colors.surface3, UserBubbleShape).padding(PipkinTokens.Spacing.s16),
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      if (item.images.isNotEmpty()) UserImages(item.images)
      if (item.text.isNotEmpty()) {
        SelectionContainer {
          Text(
            text = item.text,
            color = colors.foreground,
            fontSize = PipkinTokens.FontSize.content,
            lineHeight = PipkinTokens.LineHeight.markdown,
          )
        }
      }
    }
    Row(
      modifier = Modifier.padding(top = PipkinTokens.Spacing.s4).heightIn(min = 28.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      MessageTime(item.timestamp)
      if (item.text.isNotEmpty()) {
        CopyButton(
          text = item.text,
          contentDescription = stringResource(R.string.chat_copy_message),
          tint = colors.foregroundMuted,
          iconSize = PipkinTokens.IconSize.sm,
        )
      }
    }
  }
}

@Composable
private fun UserImages(images: List<ImagePart>) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8, Alignment.End),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    images.forEach { ImageThumbnail(it) }
  }
}

@Composable
internal fun ImageThumbnail(part: ImagePart) {
  val bitmap by produceState<ImageBitmap?>(null, part) { value = ChatImages.thumbnail(part) }
  val shape = RoundedCornerShape(PipkinTokens.Radius.md)
  val image = bitmap
  if (image == null) {
    Box(modifier = Modifier.size(ImageMaxEdge).clip(shape).background(pipkinColors.surface2))
    return
  }
  val aspect = image.width.toFloat() / image.height.coerceAtLeast(1)
  val size = if (aspect >= 1f) {
    DpSize(ImageMaxEdge, ImageMaxEdge / aspect)
  } else {
    DpSize(ImageMaxEdge * aspect, ImageMaxEdge)
  }
  Image(
    bitmap = image,
    contentDescription = stringResource(R.string.chat_image),
    modifier = Modifier.size(size).clip(shape),
    contentScale = ContentScale.Crop,
  )
}

/**
 * A block of the agent's reply; once it finished streaming, Paseo's copy button and its time sit under it. Its parse is
 * kept in [markdownCache] once it has finished, not for every text it streams through.
 */
@Composable
internal fun AssistantMessage(
  item: TimelineItem.AssistantText,
  markdownCache: MarkdownCache,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth().padding(top = PipkinTokens.Spacing.s12)) {
    PipkinMarkdown(
      markdown = item.markdown,
      modifier = Modifier.fillMaxWidth(),
      cache = markdownCache.takeUnless { item.streaming },
    )
    if (item.streaming) {
      Spacer(modifier = Modifier.height(PipkinTokens.Spacing.s12))
      return@Column
    }
    Row(
      modifier = Modifier.padding(top = PipkinTokens.Spacing.s4),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      CopyButton(
        text = item.markdown,
        contentDescription = stringResource(R.string.chat_copy_reply),
        tint = pipkinColors.foregroundMuted,
        iconSize = PipkinTokens.IconSize.sm,
      )
      MessageTime(item.timestamp)
    }
  }
}

/** When a message was sent, as `2:47 PM` in the device's time zone. */
@Composable
internal fun MessageTime(timestamp: Long) {
  val time = remember(timestamp) { TimeFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())) }
  Text(text = time, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.meta)
}

/** Paseo's compact tool-call row: icon badge, title, muted one-line summary. */
@Composable
internal fun ToolRow(presentation: ToolPresentation, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(PipkinTokens.Radius.lg))
        .background(colors.surface1)
        .clickable(onClick = onClick)
        .padding(horizontal = PipkinTokens.Spacing.s8, vertical = PipkinTokens.Spacing.s6),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
      if (presentation.running) {
        SimmeringPot(size = PipkinTokens.IconSize.sm)
      } else {
        LucideIcon(
          icon = presentation.icon,
          contentDescription = null,
          size = PipkinTokens.IconSize.sm,
          tint = if (presentation.isError) colors.destructive else colors.foregroundMuted,
        )
      }
    }
    Text(
      text = presentation.title,
      color = if (presentation.isError) colors.destructive else colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
      maxLines = 1,
    )
    Text(
      text = presentation.summary.orEmpty(),
      modifier = Modifier.weight(1f),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
internal fun ErrorRow(message: String, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = PipkinTokens.Spacing.s8),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    LucideIcon(
      icon = R.drawable.ic_lucide_circle_alert,
      contentDescription = null,
      modifier = Modifier.padding(top = PipkinTokens.Spacing.s2),
      tint = colors.destructive,
    )
    SelectionContainer(modifier = Modifier.weight(1f)) {
      Text(text = message, color = colors.destructive, fontSize = PipkinTokens.FontSize.base)
    }
  }
}

@Composable
internal fun NoticeRow(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier.fillMaxWidth().padding(vertical = PipkinTokens.Spacing.s4),
    color = pipkinColors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.meta,
    textAlign = TextAlign.Center,
  )
}

/** Live `m:ss` while the run works, then how long it took. */
@Composable
internal fun TurnFooterRow(item: TimelineItem.TurnFooter, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = 28.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    val duration = item.durationMillis
    if (duration != null) {
      Text(text = durationText(duration), color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.meta)
      return@Row
    }
    SimmeringPot(size = PipkinTokens.IconSize.sm)
    RunningElapsed(item.startedAt)
  }
}

@Composable
private fun RunningElapsed(startedAt: Long) {
  val now by produceState(System.currentTimeMillis(), startedAt) {
    while (true) {
      value = System.currentTimeMillis()
      delay(1_000L - (value - startedAt).mod(1_000L))
    }
  }
  val seconds = ((now - startedAt) / 1_000L).coerceAtLeast(0)
  Text(
    text = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
    color = pipkinColors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.meta,
  )
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
