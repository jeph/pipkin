package io.jeph.pipkin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.agent.TimelineState
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Scrolls past the end of the last item; the list clamps it to the bottom. */
private const val FOLLOW_OFFSET = 1_000_000
private const val BANNER_KEY = "pipkin:banner"

/** How far above the bottom a settled scroll still counts as at the bottom. */
private val FOLLOW_TOLERANCE = 4.dp

private val EMPTY_STATE_ICON_SIZE = 72.dp

/** An adaptive icon's 108dp layer over the 72dp a launcher shows. */
private const val LAUNCHER_MASK_SCALE = 1.5f

/**
 * The chat stream: process banner, timeline items, the empty state and Paseo's scroll-to-bottom button.
 * Keeps the end in view while the user is at the bottom; dragging away stops following.
 */
@Composable
internal fun ChatStream(
  process: AgentProcess,
  timeline: TimelineState,
  processState: AgentProcessState,
  follow: MutableState<Boolean>,
  bottomPadding: Dp,
  onOpenTool: (key: String) -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = remember(process) { LazyListState() }
  val scope = rememberCoroutineScope()
  val banner = processState.takeIf {
    it is AgentProcessState.Starting || it is AgentProcessState.Installing || it is AgentProcessState.Failed
  }
  val count = timeline.items.size + if (banner != null) 1 else 0
  val threshold = with(LocalDensity.current) { 120.dp.roundToPx() }
  AutoFollow(listState, follow)
  val showScrollButton by remember(listState, threshold) {
    derivedStateOf { listState.distanceFromBottom() > threshold }
  }
  Box(modifier = modifier.fillMaxSize()) {
    StreamList(listState, timeline.items, banner, process, bottomPadding, onOpenTool)
    if (timeline.items.isEmpty() && processState == AgentProcessState.Ready) {
      EmptyState(
        noModel = timeline.model == null,
        onOpenSettings,
        Modifier.align(Alignment.Center).padding(bottom = bottomPadding),
      )
    }
    ScrollToBottomButton(
      visible = showScrollButton,
      onClick = {
        follow.value = true
        scope.launch { if (count > 0) listState.scrollToItem(count - 1, FOLLOW_OFFSET) }
      },
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + PipkinTokens.Spacing.s16),
    )
  }
}

/**
 * Pins the end of the stream to the bottom of the viewport while following. It reacts to layout,
 * not to timeline changes, because markdown parses off the main thread: a message grows frames
 * after its text arrives. A drag stops following, and only a user scroll that settles at the
 * bottom resumes it, so a reader above the bottom is never moved.
 */
@Composable
private fun AutoFollow(listState: LazyListState, follow: MutableState<Boolean>) {
  val tolerance = with(LocalDensity.current) { FOLLOW_TOLERANCE.roundToPx() }
  LaunchedEffect(listState) {
    listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow.value = false }
  }
  LaunchedEffect(listState, tolerance) {
    // Only turns following on: the pinning's own scrolls also settle, and content that grew
    // meanwhile must not read as the user leaving the bottom.
    snapshotFlow { listState.isScrollInProgress }
      .filter { !it }
      .collect { if (!follow.value && listState.distanceFromBottom() <= tolerance) follow.value = true }
  }
  LaunchedEffect(listState) {
    snapshotFlow { follow.value to listState.distanceFromBottom() }
      .collect { (following, distance) -> if (following && distance != 0) pin(listState, distance) }
  }
}

/** Scrolls the end into view; a user drag that takes over the list cancels only this scroll. */
private suspend fun pin(listState: LazyListState, distance: Int) {
  try {
    if (distance == Int.MAX_VALUE) {
      listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, FOLLOW_OFFSET)
    } else {
      listState.scrollBy(distance.toFloat())
    }
  } catch (_: CancellationException) {
    // A higher-priority scroll owns the list; rethrow only if this effect itself was cancelled.
    currentCoroutineContext().ensureActive()
  }
}

@Composable
private fun StreamList(
  listState: LazyListState,
  items: List<TimelineItem>,
  banner: AgentProcessState?,
  process: AgentProcess,
  bottomPadding: Dp,
  onOpenTool: (key: String) -> Unit,
) {
  val replies = remember(items) { replyTexts(items) }
  val anchor = remember(listState) { BottomAnchor(listState) }
  LazyColumn(
    state = listState,
    modifier = Modifier
      .layout { measurable, constraints ->
        anchor.onHeight(constraints.maxHeight - bottomPadding.roundToPx())
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
      }.fillMaxSize(),
    contentPadding = PaddingValues(
      start = PipkinTokens.Spacing.s16,
      end = PipkinTokens.Spacing.s16,
      bottom = bottomPadding + PipkinTokens.Spacing.s16,
    ),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (banner != null) {
      item(key = BANNER_KEY, contentType = BANNER_KEY) {
        StatusBanner(banner, onRetry = process::restart, Modifier.contentWidth().padding(top = PipkinTokens.Spacing.s8))
      }
    }
    itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, item -> item::class }) { index, item ->
      StreamItem(
        item = item,
        replyText = replies[item.key],
        onOpenTool = onOpenTool,
        onRestart = process::restart,
        modifier = Modifier.contentWidth().padding(top = gapBefore(items.getOrNull(index - 1), item)),
      )
    }
  }
}

/**
 * Keeps what sits at the bottom of the visible area in place when that area's height changes, as
 * when the keyboard opens or the composer grows: the keyboard never covers what the user was
 * reading, and the top of the list scrolls up under the header instead. It runs before the list
 * measures, so the shift lands in the same frame as the resize; at the bottom it also keeps following.
 */
private class BottomAnchor(private val listState: LazyListState) {
  private var height = UNMEASURED

  /** [newHeight] is the list's height above the composer drawn over it. */
  fun onHeight(newHeight: Int) {
    val shrink = if (height == UNMEASURED) 0 else height - newHeight
    height = newHeight
    if (shrink == 0) return
    // Read without observing: the scroll position is not an input to this layout step.
    Snapshot.withoutReadObservation {
      listState.requestScrollToItem(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset + shrink,
      )
    }
  }

  private companion object {
    const val UNMEASURED = -1
  }
}

@Composable
private fun StreamItem(
  item: TimelineItem,
  replyText: String?,
  onOpenTool: (key: String) -> Unit,
  onRestart: () -> Unit,
  modifier: Modifier,
) {
  when (item) {
    is TimelineItem.User -> UserMessage(item, modifier)
    is TimelineItem.AssistantText -> AssistantMessage(item, modifier)
    is TimelineItem.Thinking -> ToolRow(toolPresentation(item), onClick = { onOpenTool(item.key) }, modifier)
    is TimelineItem.ToolCall -> ToolRow(toolPresentation(item), onClick = { onOpenTool(item.key) }, modifier)
    is TimelineItem.Error -> ErrorRow(item.message, modifier)
    is TimelineItem.Notice -> NoticeRow(item.text, modifier)
    is TimelineItem.TurnFooter -> TurnFooterRow(item, replyText, modifier)
    is TimelineItem.ExitBanner -> ExitBannerCard(item, onRestart, modifier)
  }
}

/** Pipkin's mark over "Message Pipkin to start", or a way to Settings when there is no model to use. */
@Composable
private fun EmptyState(noModel: Boolean, onOpenSettings: () -> Unit, modifier: Modifier) {
  Column(
    modifier = modifier.padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s48),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    // The launcher icon as on the home screen: its multi-colour mark on the cream background. A
    // launcher shows the middle 72dp of the 108dp foreground, so draw it 1.5x larger and clip.
    Box(
      modifier = Modifier
        .size(EMPTY_STATE_ICON_SIZE)
        .clip(CircleShape)
        .background(colorResource(R.color.ic_launcher_background)),
      contentAlignment = Alignment.Center,
    ) {
      Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.requiredSize(EMPTY_STATE_ICON_SIZE * LAUNCHER_MASK_SCALE),
      )
    }
    Text(
      text = stringResource(if (noModel) R.string.chat_no_model else R.string.chat_empty),
      color = pipkinColors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
    )
    if (noModel) Button(onClick = onOpenSettings) { Text(text = stringResource(R.string.chat_sign_in)) }
  }
}

@Composable
private fun ScrollToBottomButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier) {
  AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
    Box(
      modifier = Modifier.size(48.dp).clip(CircleShape).background(pipkinColors.surface2).clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
    ) {
      LucideIcon(
        icon = R.drawable.ic_lucide_chevron_down,
        contentDescription = stringResource(R.string.chat_scroll_to_bottom),
        size = 24.dp,
      )
    }
  }
}

/** Pixels between the end of the last item and the bottom of the viewport; MAX_VALUE while it is off screen. */
private fun LazyListState.distanceFromBottom(): Int {
  val info = layoutInfo
  val last = info.visibleItemsInfo.lastOrNull() ?: return 0
  if (last.index < info.totalItemsCount - 1) return Int.MAX_VALUE
  return (last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)).coerceAtLeast(0)
}

/** Paseo's rhythm: 4dp inside a run of user messages or tool rows, 16dp around user-message groups. */
private fun gapBefore(previous: TimelineItem?, item: TimelineItem): Dp = when {
  previous == null -> PipkinTokens.Spacing.s16
  previous is TimelineItem.User && item is TimelineItem.User -> PipkinTokens.Spacing.s4
  previous is TimelineItem.User || item is TimelineItem.User -> PipkinTokens.Spacing.s16
  else -> PipkinTokens.Spacing.s4
}

/** Each settled turn footer's last assistant text, for its copy button. */
private fun replyTexts(items: List<TimelineItem>): Map<String, String> {
  val replies = HashMap<String, String>()
  var last: String? = null
  for (item in items) {
    when (item) {
      is TimelineItem.AssistantText -> last = item.markdown

      is TimelineItem.TurnFooter -> {
        last?.let { replies[item.key] = it }
        last = null
      }

      else -> Unit
    }
  }
  return replies
}

private fun Modifier.contentWidth(): Modifier = widthIn(max = PipkinTokens.contentMaxWidth).fillMaxWidth()
