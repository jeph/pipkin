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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.StepStream
import io.jeph.pipkin.agent.StreamRows
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.agent.TimelineState
import io.jeph.pipkin.agent.text
import io.jeph.pipkin.ui.components.LoadingPill
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.MarkdownCache
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Scrolls past the end of the last item; the list clamps it to the bottom. */
private const val FOLLOW_OFFSET = 1_000_000

/**
 * The longest a newly shown list waits for its replies' parses before drawing them unparsed. A parse is quick unless
 * the CPU is busy, as while another chat streams; waiting that out would show a blank chat for seconds.
 */
private const val MAX_HOLD_MILLIS = 500L

private const val BANNER_KEY = "pipkin:banner"

private val EMPTY_STATE_ICON_SIZE = 72.dp

/** An adaptive icon's 108dp layer over the 72dp a launcher shows. */
private const val LAUNCHER_MASK_SCALE = 1.5f

/**
 * The chat stream: process banner, timeline items, the empty state and Paseo's scroll-to-bottom button.
 * Keeps the end in view while the user is at the bottom; dragging away stops following. Opens where [view] says the
 * chat was left, with the replies it has parsed, and records where it is when it leaves. While Pi starts under a
 * history already shown, a pinned [LoadingPill] replaces the banner. [onRestart] retries a failed start and restarts
 * an exited Pi. The last run's footer shows the [backgroundTasks] the chat still waits on, and the open run's the
 * subagents it waits on. [subagents] is what the `subagent` tool rows show and open.
 */
@Suppress("LongParameterList") // One parameter per thing the stream shows or hands back to the chat.
@Composable
internal fun ChatStream(
  timeline: TimelineState,
  processState: AgentProcessState,
  view: ChatView,
  onOpenTool: (key: String) -> Unit,
  onOpenProviders: () -> Unit,
  onOpenOnboarding: () -> Unit,
  onRestart: () -> Unit,
  backgroundTasks: Int,
  subagents: StreamSubagents,
  modifier: Modifier = Modifier,
) {
  val scroll = view.scroll
  val scope = rememberCoroutineScope()
  val starting = processState is AgentProcessState.Starting || processState is AgentProcessState.Installing
  val loadingOverHistory = starting && timeline.items.isNotEmpty()
  val banner = processState.takeIf { !loadingOverHistory && (starting || it is AgentProcessState.Failed) }
  val stream = timeline.stream
  val rows by remember(stream) { derivedStateOf { stream.expand(view.expanded) } }
  val count = rows.items.size + if (banner != null) 1 else 0
  val listState = remember { restoredListState(scroll, stream, rows.items, banner != null) }
  val items by rememberUpdatedState(timeline.items)
  DisposableEffect(listState) {
    onDispose {
      scroll.top = listState.top()
      // Replies the chat no longer has, as after a session switch, needn't stay parsed.
      view.markdown.keepShown(items.mapNotNullTo(HashSet()) { (it as? TimelineItem.AssistantText)?.markdown })
    }
  }
  val follow = scroll.follow
  val threshold = with(LocalDensity.current) { 120.dp.roundToPx() }
  AutoFollow(listState, follow)
  val showScrollButton by remember(listState, threshold) {
    derivedStateOf { listState.distanceFromBottom() > threshold }
  }
  Box(modifier = modifier.fillMaxSize()) {
    StreamList(
      listState,
      rows,
      banner,
      follow,
      view,
      backgroundTasks,
      subagents,
      onRestart,
      onOpenOnboarding,
      onOpenTool,
    )
    if (timeline.items.isEmpty() && processState == AgentProcessState.Ready) {
      EmptyState(noModel = timeline.model == null, onOpenProviders, Modifier.align(Alignment.Center))
    }
    AnimatedVisibility(
      visible = loadingOverHistory,
      modifier = Modifier.align(Alignment.TopCenter).padding(top = PipkinTokens.Spacing.s8),
      enter = fadeIn(),
      exit = fadeOut(),
    ) {
      val installing = processState is AgentProcessState.Installing
      LoadingPill(stringResource(if (installing) R.string.chat_installing else R.string.chat_loading))
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

/**
 * A list at the row [scroll] last had at its top, or at the fold now holding that step, when the chat isn't following
 * its end and the row is still in [rows]; otherwise at its start, which a following stream pins to the end before the
 * first frame shows.
 */
private fun restoredListState(
  scroll: ChatScroll,
  stream: StepStream,
  rows: List<TimelineItem>,
  bannerShown: Boolean,
): LazyListState {
  val top = scroll.top?.takeUnless { scroll.follow.value } ?: return LazyListState()
  if (top.key == BANNER_KEY) return LazyListState(0, top.offset)
  val key = top.key as? String ?: return LazyListState()
  val row = rows.indexOfFirst { it.key == key }.takeIf { it >= 0 }
  // A step folded since: the fold's line stands where the step did.
  val index = row ?: stream.foldHolding(key)?.let { fold -> rows.indexOfFirst { it.key == fold } }?.takeIf { it >= 0 }
  return if (index == null) {
    LazyListState()
  } else {
    LazyListState(index + if (bannerShown) 1 else 0, if (row == null) 0 else top.offset)
  }
}

/** The item at the top of the viewport and how far it is scrolled past; null before the list has measured. */
private fun LazyListState.top(): StreamTop? {
  val first = layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstVisibleItemIndex } ?: return null
  return StreamTop(first.key, firstVisibleItemScrollOffset)
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

@Suppress("LongParameterList") // ChatStream's state, passed on whole.
@Composable
private fun StreamList(
  listState: LazyListState,
  rows: StreamRows,
  banner: AgentProcessState?,
  follow: State<Boolean>,
  view: ChatView,
  backgroundTasks: Int,
  subagents: StreamSubagents,
  onRestart: () -> Unit,
  onOpenOnboarding: () -> Unit,
  onOpenTool: (key: String) -> Unit,
) {
  val items = rows.items
  val recomposer = LocalView.current.findViewTreeCompositionContext() as Recomposer
  val anchor = remember(listState, follow, recomposer) { BottomAnchor(listState, follow, recomposer) }
  val gate = remember(listState) { FirstDrawGate(listState) }
  val currentItems by rememberUpdatedState(items)
  LaunchedEffect(gate) {
    snapshotFlow { currentItems.isNotEmpty() }.first { it }
    delay(MAX_HOLD_MILLIS)
    gate.stopHolding()
  }
  val firstItemIndex = if (banner != null) 1 else 0
  val itemCount = items.size + firstItemIndex
  val lastFooterKey = remember(items) { items.lastOrNull { it is TimelineItem.TurnFooter }?.key }
  val railColor = pipkinColors.border
  LazyColumn(
    state = listState,
    modifier = Modifier
      .layout { measurable, constraints ->
        anchor.beforeMeasure(constraints.maxHeight, itemCount)
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
      }.drawWithContent { if (gate.isOpen(items, firstItemIndex, view.markdown)) drawContent() }
      .fillMaxSize(),
    contentPadding = PaddingValues(
      start = PipkinTokens.Spacing.s16,
      end = PipkinTokens.Spacing.s16,
      bottom = PipkinTokens.Spacing.s16,
    ),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (banner != null) {
      item(key = BANNER_KEY, contentType = BANNER_KEY) {
        StatusBanner(
          state = banner,
          onRetry = onRestart,
          onOpenOnboarding = onOpenOnboarding,
          modifier = Modifier.contentWidth().padding(top = PipkinTokens.Spacing.s8),
        )
      }
    }
    itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, item -> item::class }) { index, item ->
      val rail = if (rows.isListedStep(item.key)) {
        Modifier.foldRail(last = rows.isLastListedStep(item.key), color = railColor)
      } else {
        Modifier
      }
      StreamItem(
        item = item,
        markdownCache = view.markdown,
        backgroundTasks = if (item.key == lastFooterKey) backgroundTasks else 0,
        subagents = subagents,
        expanded = view.expanded,
        onOpenTool = onOpenTool,
        onRestart = onRestart,
        modifier = Modifier.contentWidth().padding(top = gapBefore(items.getOrNull(index - 1), item)).then(rail),
      )
    }
  }
}

/**
 * Holds back a newly shown list's drawing until every finished reply it has laid out on screen is parsed, so a chat
 * whose replies weren't parsed ahead appears once, in its final place, instead of drawing them empty and then jumping
 * as their text lands. A list without items yet, such as a chat whose history Pi still has to send, draws at once but
 * doesn't count: the history's arrival is held back the same way. Once it has drawn items, or [stopHolding] was
 * called, it holds nothing back, so a reply scrolled to later shows empty until parsed.
 */
private class FirstDrawGate(private val listState: LazyListState) {
  private var open = false
  private var holding by mutableStateOf(true)

  /**
   * Reads the list's layout, [MarkdownCache.version] and whether it is still holding, so the list draws again when a
   * parse lands, even one that leaves the layout as it was, such as a failed parse that keeps the loading box's size,
   * and when it stops holding.
   */
  fun isOpen(items: List<TimelineItem>, firstItemIndex: Int, markdownCache: MarkdownCache): Boolean {
    if (open) return true
    markdownCache.version
    val ready = !holding || listState.layoutInfo.visibleItemsInfo.all { info ->
      val reply = items.getOrNull(info.index - firstItemIndex) as? TimelineItem.AssistantText
      reply == null || reply.streaming || markdownCache[reply.markdown] != null
    }
    open = ready && items.isNotEmpty()
    return ready
  }

  /** Draws the list from now on, parsed or not. */
  fun stopHolding() {
    holding = false
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

  /**
   * [itemCount] is the list's item count as just composed. The list's own layout info still has the count of its
   * last measure: none before its first, so a new list would show its start for a frame, and one item too many
   * after an item is removed.
   */
  fun beforeMeasure(newHeight: Int, itemCount: Int) {
    val shrink = if (height == UNMEASURED) 0 else height - newHeight
    height = newHeight
    // Content grows only when composition changed or the list resized. A pin's request remeasures the
    // list once more with neither; pinning again then would remeasure it every frame.
    val changed = recomposer.changeCount != changeCount || shrink != 0
    changeCount = recomposer.changeCount
    // Read without observing: the scroll position is not an input to this layout step.
    Snapshot.withoutReadObservation {
      when {
        follow.value && !listState.isScrollInProgress -> {
          if (changed && itemCount > 0) listState.requestScrollToItem(itemCount - 1, FOLLOW_OFFSET)
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

/**
 * [backgroundTasks] is what a [TimelineItem.TurnFooter] shows the chat still waits on, beside the [subagents] the open
 * run waits on; [expanded] holds the keys of the folds whose steps are listed and the long prompts shown whole, which
 * tapping their toggle adds or removes.
 */
@Composable
private fun StreamItem(
  item: TimelineItem,
  markdownCache: MarkdownCache,
  backgroundTasks: Int,
  subagents: StreamSubagents,
  expanded: MutableSet<String>,
  onOpenTool: (key: String) -> Unit,
  onRestart: () -> Unit,
  modifier: Modifier,
) {
  when (item) {
    is TimelineItem.User -> {
      val shown = item.key in expanded
      UserMessage(
        item,
        expanded = shown,
        onToggle = { if (shown) expanded -= item.key else expanded += item.key },
        modifier = modifier,
        task = item.key == subagents.taskKey,
      )
    }

    is TimelineItem.AssistantText -> AssistantMessage(item, markdownCache, modifier)

    is TimelineItem.ExtensionMessage -> ExtensionMessage(item, markdownCache, modifier)

    is TimelineItem.ExtensionNotice -> ExtensionNoticeCard(item, modifier)

    is TimelineItem.Thinking, is TimelineItem.ToolCall -> StepRow(item, subagents, onOpenTool, modifier)

    is TimelineItem.Error -> ErrorRow(item.cause.text(LocalResources.current), modifier)

    is TimelineItem.Notice -> NoticeRow(item.kind, modifier)

    is TimelineItem.TurnFooter -> {
      // Only the open run's footer has no duration yet, and only that run waits on subagents.
      val waitedOn = if (item.durationMillis == null) subagents.chat.active else 0
      TurnFooterRow(item, backgroundTasks, modifier, subagents = waitedOn)
    }

    is TimelineItem.ExitBanner -> ExitBannerCard(item, onRestart, modifier)

    is TimelineItem.Steps -> {
      val shown = item.key in expanded
      StepFoldRow(
        fold = item,
        expanded = shown,
        onToggle = { if (shown) expanded -= item.key else expanded += item.key },
        onOpenTool = onOpenTool,
        subagents = subagents,
        modifier = modifier,
      )
    }
  }
}

/** Pipkin's mark over "Message Pipkin to start", or a way to Providers when there is no model to use. */
@Composable
private fun EmptyState(noModel: Boolean, onOpenProviders: () -> Unit, modifier: Modifier) {
  Column(
    modifier = modifier.padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s48),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    // The launcher icon's mark in the theme's accent, on a wash of it. A launcher shows the middle 72dp of the 108dp
    // foreground, so draw it 1.5x larger and clip.
    val colors = pipkinColors
    Box(
      modifier = Modifier
        .size(EMPTY_STATE_ICON_SIZE)
        .clip(CircleShape)
        .background(colors.accentSoft),
      contentAlignment = Alignment.Center,
    ) {
      Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.requiredSize(EMPTY_STATE_ICON_SIZE * LAUNCHER_MASK_SCALE),
        colorFilter = ColorFilter.tint(colors.accent),
      )
    }
    Text(
      text = stringResource(if (noModel) R.string.chat_no_model else R.string.chat_empty),
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.title,
      fontWeight = FontWeight.ExtraBold,
      textAlign = TextAlign.Center,
    )
    if (noModel) PillButton(label = stringResource(R.string.chat_sign_in), onClick = onOpenProviders)
  }
}

/** A 40dp disc on surface2, the size of the composer's send button, with a chevron down. */
@Composable
private fun ScrollToBottomButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier) {
  AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
    RoundIconButton(
      icon = R.drawable.ic_lucide_chevron_down,
      contentDescription = stringResource(R.string.chat_scroll_to_bottom),
      onClick = onClick,
      modifier = Modifier.size(40.dp),
      background = pipkinColors.surface2,
      iconSize = 20.dp,
    )
  }
}

/** Pixels between the end of the last item and the bottom of the viewport; MAX_VALUE while it is off screen. */
private fun LazyListState.distanceFromBottom(): Int {
  val info = layoutInfo
  val last = info.visibleItemsInfo.lastOrNull() ?: return 0
  if (last.index < info.totalItemsCount - 1) return Int.MAX_VALUE
  return (last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)).coerceAtLeast(0)
}

/** 12dp above a user message and 8dp after one, none between steps, so a run of them reads as a list, else 4dp. */
private fun gapBefore(previous: TimelineItem?, item: TimelineItem): Dp = when {
  previous == null -> PipkinTokens.Spacing.s8
  item is TimelineItem.User -> PipkinTokens.Spacing.s12
  previous is TimelineItem.User -> PipkinTokens.Spacing.s8
  previous.isStep && item.isStep -> 0.dp
  else -> PipkinTokens.Spacing.s4
}

private val TimelineItem.isStep: Boolean
  get() = this is TimelineItem.ToolCall || this is TimelineItem.Thinking || this is TimelineItem.Steps

private fun Modifier.contentWidth(): Modifier = widthIn(max = PipkinTokens.contentMaxWidth).fillMaxWidth()
