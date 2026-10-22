package io.jeph.pipkin.ui.chat

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.NoticeLevel
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.agent.text
import io.jeph.pipkin.ui.components.CapsLabel
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.MarkdownCache
import io.jeph.pipkin.ui.components.PipkinMarkdown
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.math.roundToInt

/** How many lines a folded extension message or notice shows. */
private const val FOLDED_LINES = 6

/** How many lines past [FOLDED_LINES] content may run before it folds; paragraph spacing makes real lines taller. */
private const val FOLD_SLACK_LINES = 3

/** The notice's line height. */
private val NOTICE_LINE_HEIGHT = 20.sp

private val CardShape = RoundedCornerShape(16.dp)

/**
 * A message an extension posted, as Pi's terminal shows it: the extension's message type in plum over its content, on
 * a raised card. Long text folds behind Show more; images always show whole.
 */
@Composable
internal fun ExtensionMessage(
  item: TimelineItem.ExtensionMessage,
  markdownCache: MarkdownCache,
  modifier: Modifier = Modifier,
) {
  val colors = pipkinColors
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = PipkinTokens.Spacing.s4)
      .background(colors.surface1, CardShape)
      .padding(start = PipkinTokens.Spacing.s12, top = 10.dp, end = PipkinTokens.Spacing.s12, bottom = 10.dp),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      LucideIcon(
        icon = R.drawable.ic_lucide_plug,
        contentDescription = stringResource(R.string.chat_extension_message),
        size = PipkinTokens.IconSize.sm,
        tint = colors.extension,
      )
      Text(
        text = item.customType,
        color = colors.extension,
        fontSize = PipkinTokens.FontSize.sm,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (item.markdown.isNotBlank()) {
      var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
      FoldedContent(
        FOLDED_LINES,
        FOLD_SLACK_LINES,
        PipkinTokens.LineHeight.markdown,
        fadeTo = colors.surface1,
        expanded = expanded,
        onToggle = { expanded = !expanded },
      ) {
        PipkinMarkdown(markdown = item.markdown, modifier = Modifier.fillMaxWidth(), cache = markdownCache)
      }
    }
    if (item.images.isNotEmpty()) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
        verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
      ) {
        item.images.forEach { ImageThumbnail(it) }
      }
    }
  }
}

/**
 * A notice an extension showed with `ctx.ui.notify`: a card washed in its level's colour, its level word over the
 * text, so the colour isn't the only signal. Long notices fold like extension messages, and the text can be selected.
 */
@Composable
internal fun ExtensionNoticeCard(item: TimelineItem.ExtensionNotice, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  val look = when (item.level) {
    NoticeLevel.Info -> NoticeLook(R.drawable.ic_lucide_info, R.string.chat_notice_info, colors.info, colors.infoSoft)

    NoticeLevel.Warning -> NoticeLook(
      R.drawable.ic_lucide_triangle_alert,
      R.string.chat_notice_warning,
      colors.dotWarning,
      colors.warningSoft,
    )

    NoticeLevel.Error ->
      NoticeLook(R.drawable.ic_lucide_circle_x, R.string.chat_notice_error, colors.destructive, colors.dangerSoft)
  }
  // Opaque, so a folded notice's fade ends in the card's own colour.
  val background = look.wash.compositeOver(colors.surface0)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(background, CardShape)
      .padding(horizontal = PipkinTokens.Spacing.s12, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(11.dp),
  ) {
    LucideIcon(icon = look.icon, contentDescription = null, modifier = Modifier.padding(top = 1.dp), tint = look.tint)
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      CapsLabel(stringResource(look.level), look.tint)
      var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
      FoldedContent(
        FOLDED_LINES,
        FOLD_SLACK_LINES,
        NOTICE_LINE_HEIGHT,
        fadeTo = background,
        expanded = expanded,
        onToggle = { expanded = !expanded },
      ) {
        SelectionContainer {
          Text(
            text = item.appNotice?.let { stringResource(it.text) } ?: item.message,
            color = colors.foreground,
            fontSize = 13.5.sp,
            lineHeight = NOTICE_LINE_HEIGHT,
          )
        }
      }
    }
  }
}

private data class NoticeLook(@DrawableRes val icon: Int, @StringRes val level: Int, val tint: Color, val wash: Color)
