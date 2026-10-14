package io.jeph.pipkin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.agent.TimelineState
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/** Scrolls past the end of the last item; the list clamps it to the bottom. */
private const val FOLLOW_OFFSET = 1_000_000
private const val BANNER_KEY = "pipkin:banner"

private val EMPTY_STATE_ICON_SIZE = 72.dp

/** An adaptive icon's 108dp layer over the 72dp a launcher shows. */
private const val LAUNCHER_MASK_SCALE = 1.5f

/**
 * The chat stream: process banner, timeline items, the empty state and Paseo's scroll-to-bottom button.
 * Keeps the end in view while the user is at the bottom; dragging away stops following. While Pi starts under a
 * history already shown, a pinned [LoadingPill] replaces the banner. [onRestart] retries a failed start and
 * restarts an exited Pi.
 */
@Composable
internal fun ChatStream(
  timeline: TimelineState,
  processState: AgentProcessState,
  follow: MutableState<Boolean>,
  onOpenTool: (key: String) -> Unit,
  onOpenSettings: () -> Unit,
  onRestart: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = remember { LazyListState() }
  val scope = rememberCoroutineScope()
  val starting = processState is AgentProcessState.Starting || processState is AgentProcessState.Installing
  val loadingOverHistory = starting && timeline.items.isNotEmpty()
  val banner = processState.takeIf { !loadingOverHistory && (starting || it is AgentProcessState.Failed) }
  val count = timeline.items.size + if (banner != null) 1 else 0
  val threshold = with(LocalDensity.current) { 120.dp.roundToPx() }
  AutoFollow(listState, follow)
  val showScrollButton by remember(listState, threshold) {
    derivedStateOf { listState.distanceFromBottom() > threshold }
  }
  Box(modifier = modifier.fillMaxSize()) {
    StreamList(listState, timeline.items, banner, follow, onRestart, onOpenTool)
    if (timeline.items.isEmpty() && processState == AgentProcessState.Ready) {
      EmptyState(noModel = timeline.model == null, onOpenSettings, Modifier.align(Alignment.Center))
    }
    AnimatedVisibility(
      visible = loadingOverHistory,
      modifier = Modifier.align(Alignment.TopCenter).padding(top = PipkinTokens.Spacing.s8),
      enter = fadeIn(),
      exit = fadeOut(),
    ) {
      LoadingPill(installing = processState is AgentProcessState.Installing)
    }
    ScrollToBottomButton(
      visible = showScrollButton,
      onClick = {
        follow.value = true
        scope.launch { if (count > 0) listState.scrollToItem(count - 1, FOLLOW_OFFSET) }
      },
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = PipkinTokens.Spacing.s16),
    )
  }
}

/** [AutoFollow] for the stream, which jumps to its last item while that is too far off screen to measure. */
@Composable
private fun AutoFollow(listState: LazyListState, follow: MutableState<Boolean>) {
  AutoFollow(
    state = listState,
    interactionSource = listState.interactionSource,
    follow = follow,
    distanceFromBottom = { listState.distanceFromBottom() },
    scrollDown = { distance ->
      if (distance == Int.MAX_VALUE) {
        listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, FOLLOW_OFFSET)
      } else {
        listState.scrollBy(distance.toFloat())
      }
    },
  )
}

@Composable
private fun StreamList(
  listState: LazyListState,
  items: List<TimelineItem>,
  banner: AgentProcessState?,
  follow: State<Boolean>,
  onRestart: () -> Unit,
  onOpenTool: (key: String) -> Unit,
) {
  val recomposer = LocalView.current.findViewTreeCompositionContext() as Recomposer
  val anchor = remember(listState, follow, recomposer) { BottomAnchor(listState, follow, recomposer) }
  LazyColumn(
    state = listState,
    modifier = Modifier
      .layout { measurable, constraints ->
        anchor.beforeMeasure(constraints.maxHeight)
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
      }.fillMaxSize(),
    contentPadding = PaddingValues(
      start = PipkinTokens.Spacing.s16,
      end = PipkinTokens.Spacing.s16,
      bottom = PipkinTokens.Spacing.s16,
    ),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (banner != null) {
      item(key = BANNER_KEY, contentType = BANNER_KEY) {
        StatusBanner(banner, onRetry = onRestart, Modifier.contentWidth().padding(top = PipkinTokens.Spacing.s8))
      }
    }
    itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, item -> item::class }) { index, item ->
      StreamItem(
        item = item,
        onOpenTool = onOpenTool,
        onRestart = onRestart,
        modifier = Modifier.contentWidth().padding(top = gapBefore(items.getOrNull(index - 1), item)),
      )
    }
  }
}

/**
 * Runs before the list measures, so a scroll it requests lands in the same frame as whatever changed the
 * layout. While [follow] is on it pins the end to the bottom, so content that grows is never drawn for a
 * frame below where it settles. Otherwise it keeps what sits at the bottom of the viewport in place when the
 * list's height changes, as when the keyboard opens or the composer grows: the keyboard never covers what
 * the user was reading, and the top of the list scrolls up under the header instead.
 */
private class BottomAnchor(
  private val listState: LazyListState,
  private val follow: State<Boolean>,
  private val recomposer: Recomposer,
) {
  private var height = UNMEASURED
  private var changeCount = -1L

  fun beforeMeasure(newHeight: Int) {
    val shrink = if (height == UNMEASURED) 0 else height - newHeight
    height = newHeight
    // Content grows only when composition changed or the list resized. A pin's request remeasures the
    // list once more with neither; pinning again then would remeasure it every frame.
    val changed = recomposer.changeCount != changeCount || shrink != 0
    changeCount = recomposer.changeCount
    // Read without observing: the scroll position is not an input to this layout step.
    Snapshot.withoutReadObservation {
      val count = listState.layoutInfo.totalItemsCount
      when {
        follow.value && !listState.isScrollInProgress -> {
          if (changed && count > 0) listState.requestScrollToItem(count - 1, FOLLOW_OFFSET)
        }

        shrink != 0 -> listState.requestScrollToItem(
          listState.firstVisibleItemIndex,
          listState.firstVisibleItemScrollOffset + shrink,
        )
      }
    }
  }

  private companion object {
    const val UNMEASURED = -1
  }
}

@Composable
private fun StreamItem(
  item: TimelineItem,
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
    is TimelineItem.TurnFooter -> TurnFooterRow(item, modifier)
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

private fun Modifier.contentWidth(): Modifier = widthIn(max = PipkinTokens.contentMaxWidth).fillMaxWidth()
