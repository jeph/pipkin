package io.jeph.pipkin.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ChatLocation
import io.jeph.pipkin.agent.InboxEntry
import io.jeph.pipkin.agent.InboxReason
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.components.HeaderBarHeight
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.delay

/**
 * The chat inbox under the header bar, below what [above] shows there: while [shown] and any chat is in it, a small
 * pill naming the chat and what happened, or how many chats there are. Tapping the pill opens its only chat through
 * [onOpen], or with several opens a list of them to pick from or clear; swiping it sideways clears them all. A chat
 * leaves the inbox once it is opened.
 */
@Composable
internal fun ChatInboxOverlay(shown: Boolean, onOpen: (ChatLocation) -> Unit, above: @Composable () -> Unit) {
  val inbox = LocalContext.current.appGraph.chatInbox
  val entries by inbox.entries.collectAsStateWithLifecycle()
  var expanded by rememberSaveable { mutableStateOf(false) }
  val pillShown = shown && entries.isNotEmpty()
  val trayShown = pillShown && expanded
  LaunchedEffect(pillShown) { if (!pillShown) expanded = false }
  // Composed only while open, so it registers after the screens' own Back handlers and takes Back first.
  if (trayShown) BackHandler { expanded = false }
  val open = { entry: InboxEntry ->
    expanded = false
    inbox.remove(entry.location.sessionPath)
    onOpen(entry.location)
  }
  Box(modifier = Modifier.fillMaxSize()) {
    AnimatedVisibility(visible = trayShown, enter = fadeIn(), exit = fadeOut()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = SCRIM_ALPHA))
          .clickable(interactionSource = null, indication = null) { expanded = false },
      )
    }
    Column(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(top = HeaderBarHeight + PipkinTokens.Spacing.s8)
        .padding(horizontal = PipkinTokens.Spacing.s8),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      above()
      AnimatedVisibility(visible = pillShown, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut()) {
        // Keyed on the oldest chat, so after a swipe clears the pill the next one starts unswiped.
        key(entries.lastOrNull()) {
          InboxPill(
            entries = entries,
            expanded = expanded,
            onTap = { entries.singleOrNull()?.let(open) ?: run { expanded = !expanded } },
            onClear = inbox::clear,
          )
        }
      }
      AnimatedVisibility(
        visible = trayShown,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
      ) {
        InboxTray(entries = entries, onOpen = open, onClearAll = inbox::clear)
      }
    }
  }
}

/** An inverse pill, so it stands out over any chat: one chat's badge, title and what happened, or several as dots. */
@Composable
private fun InboxPill(entries: List<InboxEntry>, expanded: Boolean, onTap: () -> Unit, onClear: () -> Unit) {
  if (entries.isEmpty()) return
  val colors = pipkinColors
  val single = entries.singleOrNull()
  val clearLabel = stringResource(R.string.inbox_clear_all)
  val tapLabel = stringResource(
    when {
      single != null -> R.string.inbox_open_chat
      expanded -> R.string.inbox_hide_chats
      else -> R.string.inbox_show_chats
    },
  )
  SwipeToDismissBox(
    state = rememberSwipeToDismissBoxState(),
    backgroundContent = {},
    onDismiss = { onClear() },
    modifier = Modifier.semantics {
      liveRegion = LiveRegionMode.Polite
      // Screen reader users can't swipe it away, so clearing is an action instead.
      customActions = listOf(
        CustomAccessibilityAction(clearLabel) {
          onClear()
          true
        },
      )
    },
  ) {
    Row(
      modifier = Modifier
        .widthIn(max = PILL_MAX_WIDTH)
        .shadow(PILL_ELEVATION, CircleShape)
        .clip(CircleShape)
        .background(colors.foreground)
        .clickable(onClickLabel = tapLabel, onClick = onTap)
        .padding(start = 7.dp, end = PipkinTokens.Spacing.s12)
        .heightIn(min = PILL_HEIGHT),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      if (single != null) SinglePillContent(single) else MergedPillContent(entries)
    }
  }
}

@Composable
private fun RowScope.SinglePillContent(entry: InboxEntry) {
  val colors = pipkinColors
  ReasonBadge(entry.reason, PILL_BADGE_SIZE)
  Text(
    text = entry.title ?: stringResource(R.string.chat_new_chat),
    modifier = Modifier.weight(1f, fill = false),
    color = colors.surface0,
    fontSize = PILL_TEXT_SIZE,
    fontWeight = FontWeight.SemiBold,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
  Text(
    text = reasonText(entry),
    color = colors.surface0.copy(alpha = PILL_DIM_ALPHA),
    fontSize = PILL_TEXT_SIZE,
    fontWeight = FontWeight.Medium,
    maxLines = 1,
    softWrap = false,
  )
}

@Composable
private fun RowScope.MergedPillContent(entries: List<InboxEntry>) {
  val colors = pipkinColors
  Row(horizontalArrangement = Arrangement.spacedBy(-DOT_OVERLAP)) {
    for (entry in entries.take(MAX_DOTS)) {
      Box(
        modifier = Modifier
          .size(DOT_SIZE)
          .clip(CircleShape)
          .background(colors.foreground)
          .padding(DOT_RING)
          .clip(CircleShape)
          .background(entry.reason.color()),
      )
    }
  }
  val count = entries.size
  val waiting = entries.count { it.reason == InboxReason.Waiting }
  val chats = pluralStringResource(R.plurals.inbox_chats, count, count)
  val waitingText = if (waiting > 0) {
    stringResource(R.string.separator_dot) + pluralStringResource(R.plurals.inbox_waiting_count, waiting, waiting)
  } else {
    ""
  }
  val dim = SpanStyle(color = colors.surface0.copy(alpha = PILL_DIM_ALPHA), fontWeight = FontWeight.Medium)
  Text(
    text = buildAnnotatedString {
      append(chats)
      withStyle(dim) { append(waitingText) }
    },
    modifier = Modifier.weight(1f, fill = false),
    color = colors.surface0,
    fontSize = PILL_TEXT_SIZE,
    fontWeight = FontWeight.SemiBold,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/** The chats in the inbox, newest first, under a header with Clear all; tapping one opens it through [onOpen]. */
@Composable
private fun InboxTray(entries: List<InboxEntry>, onOpen: (InboxEntry) -> Unit, onClearAll: () -> Unit) {
  val colors = pipkinColors
  val now = rememberMinuteClock()
  Column(
    modifier = Modifier
      .widthIn(max = TRAY_MAX_WIDTH)
      .fillMaxWidth()
      .shadow(TRAY_ELEVATION, TRAY_SHAPE)
      .clip(TRAY_SHAPE)
      .background(colors.surface1)
      .padding(
        start = PipkinTokens.Spacing.s8,
        top = 10.dp,
        end = PipkinTokens.Spacing.s8,
        bottom = PipkinTokens.Spacing.s8,
      ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 10.dp, bottom = PipkinTokens.Spacing.s4),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.inbox_tray_title),
        modifier = Modifier.weight(1f),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.lg,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = stringResource(R.string.inbox_clear_all),
        modifier = Modifier
          .clip(CircleShape)
          .clickable(onClick = onClearAll)
          .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s8),
        color = colors.accent,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
    Column(
      modifier = Modifier.heightIn(max = TRAY_LIST_MAX_HEIGHT).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s2),
    ) {
      for (entry in entries) {
        key(entry.location.sessionPath) { InboxRow(entry, now, onOpen) }
      }
    }
  }
}

@Composable
private fun InboxRow(entry: InboxEntry, now: Long, onOpen: (InboxEntry) -> Unit) {
  val colors = pipkinColors
  val detail = entry.detail?.lineSequence()?.map(String::trim)?.firstOrNull(String::isNotEmpty)
    ?: if (entry.reason == InboxReason.Waiting) stringResource(R.string.dialog_notification_text) else null
  val reason = reasonText(entry)
  val asker = entry.waitingOn
  val separator = stringResource(R.string.separator_dot)
  val askerStyle = SpanStyle(color = colors.foreground, fontWeight = FontWeight.SemiBold)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(ROW_SHAPE)
      .clickable(onClickLabel = stringResource(R.string.inbox_open_chat)) { onOpen(entry) }
      .padding(PipkinTokens.Spacing.s8),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    ReasonBadge(entry.reason, ROW_BADGE_SIZE)
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = entry.title ?: stringResource(R.string.chat_new_chat),
        color = colors.foreground,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = buildAnnotatedString {
          append(reason)
          // The subagent's name stands out, as the chat's title does above it.
          val start = asker?.let { reason.indexOf(it) } ?: -1
          if (asker != null && start >= 0) addStyle(askerStyle, start, start + asker.length)
          detail?.let { append(separator + it) }
        },
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      text = timeAgo(entry.at, now),
      modifier = Modifier.padding(top = PipkinTokens.Spacing.s2),
      color = colors.foregroundExtraMuted,
      fontSize = PipkinTokens.FontSize.sm,
      maxLines = 1,
    )
  }
}

/** What happened to a chat, as a mark on a disc in its colour. */
@Composable
private fun ReasonBadge(reason: InboxReason, size: Dp) {
  Box(
    modifier = Modifier.size(size).clip(CircleShape).background(reason.color()),
    contentAlignment = Alignment.Center,
  ) {
    LucideIcon(
      icon = when (reason) {
        InboxReason.Finished -> R.drawable.ic_lucide_check
        InboxReason.Failed, InboxReason.Exited -> R.drawable.ic_lucide_x
        InboxReason.Waiting -> R.drawable.ic_lucide_message_circle_question
      },
      contentDescription = null,
      size = size * BADGE_ICON_SCALE,
      tint = pipkinColors.surface0,
    )
  }
}

@Composable
private fun InboxReason.color(): Color = when (this) {
  InboxReason.Finished -> pipkinColors.dotSuccess
  InboxReason.Failed -> pipkinColors.dotDanger
  InboxReason.Exited -> pipkinColors.foregroundExtraMuted
  InboxReason.Waiting -> pipkinColors.dotWarning
}

private val InboxReason.label: Int
  get() = when (this) {
    InboxReason.Finished -> R.string.inbox_finished
    InboxReason.Failed -> R.string.inbox_failed
    InboxReason.Exited -> R.string.inbox_exited
    InboxReason.Waiting -> R.string.inbox_waiting
  }

/** The subagent whose question the chat waits on, when Pipkin can tell; null for any other entry. */
private val InboxEntry.waitingOn: String?
  get() = asker?.takeIf { reason == InboxReason.Waiting }

/** What happened in [entry]'s chat, as in "needs you", or "Explore needs you" when its subagent Explore asked. */
@Composable
private fun reasonText(entry: InboxEntry): String =
  entry.waitingOn?.let { stringResource(R.string.inbox_waiting_subagent, it) } ?: stringResource(entry.reason.label)

/** The time now, moving on each minute while composed, for the list's "5 min. ago". */
@Composable
private fun rememberMinuteClock(): Long {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(DateUtils.MINUTE_IN_MILLIS)
      now = System.currentTimeMillis()
    }
  }
  return now
}

@Composable
private fun timeAgo(at: Long, now: Long): String = if (now - at < DateUtils.MINUTE_IN_MILLIS) {
  stringResource(R.string.inbox_now)
} else {
  DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

/** Wide enough for a short title and what happened; a longer title is cut short rather than cover the chat. */
private val PILL_MAX_WIDTH = 260.dp
private val PILL_HEIGHT = 34.dp
private val PILL_ELEVATION = 6.dp
private val PILL_BADGE_SIZE = 22.dp
private val PILL_TEXT_SIZE = 12.5.sp
private const val PILL_DIM_ALPHA = 0.65f
private val TRAY_SHAPE = RoundedCornerShape(26.dp)
private val TRAY_MAX_WIDTH = 400.dp
private val TRAY_LIST_MAX_HEIGHT = 320.dp
private val TRAY_ELEVATION = 12.dp
private val ROW_SHAPE = RoundedCornerShape(18.dp)
private val ROW_BADGE_SIZE = 28.dp
private const val BADGE_ICON_SCALE = 0.6f
private val DOT_SIZE = 19.dp
private val DOT_RING = 2.5.dp
private val DOT_OVERLAP = 7.dp
private const val MAX_DOTS = 3
private const val SCRIM_ALPHA = 0.4f
