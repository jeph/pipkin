package io.jeph.pipkin.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ExtensionWidget
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

private val TAB_HEIGHT = 26.dp
private val TAB_LABEL_MAX_WIDTH = 150.dp

/** How tall a widget's lines grow inside the composer before they scroll; the sheet shows them all. */
private val PANEL_MAX_HEIGHT = 180.dp

/** The same while the keyboard is up or a dialog waits: four lines, so the field and the dialog keep their room. */
private val PANEL_TIGHT_MAX_HEIGHT = 68.dp

private val PANEL_SHAPE = RoundedCornerShape(14.dp)
private val PANEL_FONT_SIZE = 11.sp
private val PANEL_LINE_HEIGHT = 17.sp
private val EXPAND_SIZE = 26.dp

/** Lowers the panel's lines so the first one centres on the expand button beside it: (26 - 17) / 2. */
private val PANEL_TEXT_TOP = 4.5.dp

/** How far a tab strip or a panel fades at an edge it scrolls past. */
private val SCROLL_FADE = 24.dp

private const val TAB_RISE_MILLIS = 220
private const val SHEET_MAX_HEIGHT = 0.9f

/** A widget with its key, as a tab or panel shows it. */
internal class KeyedWidget(val key: String, val widget: ExtensionWidget)

/**
 * Which widget tab is open on each edge of the composer, and which widgets changed since the user last saw them. Kept
 * in the chat's [ChatView], so switching chats leaves both as they were.
 */
@Stable
internal class WidgetTabs {
  private var openAbove by mutableStateOf<String?>(null)
  private var openBelow by mutableStateOf<String?>(null)
  private val seen = mutableStateMapOf<String, List<String>>()

  /** The key of the tab open on the editor's [belowEditor] edge; null when none is. */
  fun open(belowEditor: Boolean): String? = if (belowEditor) openBelow else openAbove

  /** Opens [key]'s tab in place of its edge's open one, or closes it when it is the open one. */
  fun toggle(key: String, belowEditor: Boolean) {
    val next = key.takeIf { it != open(belowEditor) }
    if (belowEditor) openBelow = next else openAbove = next
  }

  /** Whether [widget] changed since the user last saw it; a widget counts as seen when it first shows. */
  fun changed(key: String, widget: ExtensionWidget): Boolean = seen[key]?.let { it != widget.lines } == true

  fun see(key: String, widget: ExtensionWidget) {
    seen[key] = widget.lines
  }

  /**
   * Takes a new widget's lines as seen, so only its later changes mark its tab, and forgets widgets that are gone.
   * A tab closes when its widget goes or moves to the other edge.
   */
  fun track(widgets: Map<String, ExtensionWidget>) {
    seen.keys.retainAll(widgets.keys)
    for ((key, widget) in widgets) if (key !in seen) seen[key] = widget.lines
    if (openAbove?.let { widgets[it] }?.belowEditor != false) openAbove = null
    if (openBelow?.let { widgets[it] }?.belowEditor != true) openBelow = null
  }
}

/** The widgets on one edge of the composer, and the one whose panel shows; null while none does. */
internal class WidgetEdge(val widgets: List<KeyedWidget>, val shown: KeyedWidget?)

/** The [widgets] on the editor's [belowEditor] edge, in their order, and the open tab's widget. */
internal fun widgetEdge(widgets: Map<String, ExtensionWidget>, tabs: WidgetTabs, belowEditor: Boolean): WidgetEdge {
  val edge = widgets.filterValues { it.belowEditor == belowEditor }.map { KeyedWidget(it.key, it.value) }
  val open = tabs.open(belowEditor)
  return WidgetEdge(edge, edge.firstOrNull { it.key == open })
}

/**
 * The widgets Pi draws on one edge of the editor as tabs hanging from that edge of the composer: its top border for
 * widgets above the editor, its bottom border for those below. The strip unfolds out of the border with its first
 * widget and folds back with its last, a tab added later rises out of the border, and one removed fades. A label
 * stops at 150 dp, and a strip with more tabs than fit scrolls sideways, fading at an edge it continues past. A tab
 * opens its widget's panel, or closes it when open.
 */
@Composable
internal fun WidgetTabStrip(edge: WidgetEdge, tabs: WidgetTabs, belowEditor: Boolean, modifier: Modifier = Modifier) {
  val widgets = edge.widgets
  // Kept while the strip folds away, which it does once there are no widgets left to show.
  var shown by remember { mutableStateOf(widgets) }
  if (widgets.isNotEmpty()) shown = widgets
  val fromBorder = if (belowEditor) -1 else 1
  AnimatedVisibility(
    visible = widgets.isNotEmpty(),
    modifier = modifier,
    enter = expandVertically(expandFrom = if (belowEditor) Alignment.Top else Alignment.Bottom) +
      slideInVertically { it * fromBorder },
    exit = shrinkVertically(shrinkTowards = if (belowEditor) Alignment.Top else Alignment.Bottom) +
      slideOutVertically { it * fromBorder },
  ) {
    // The tabs the strip showed when it unfolded rose with it; any other rises on its own when it first shows.
    val risen = remember { shown.mapTo(HashSet()) { it.key } }
    val state = rememberLazyListState()
    LazyRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = PipkinTokens.Spacing.s6)
        .height(TAB_HEIGHT)
        .clipToBounds()
        .fadeScrolledEdges(state, vertical = false),
      state = state,
      contentPadding = PaddingValues(horizontal = PipkinTokens.Spacing.s2),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      items(shown, key = { it.key }) { item ->
        val rise = remember { Animatable(if (risen.add(item.key)) 1f else 0f) }
        LaunchedEffect(Unit) { rise.animateTo(0f, tween(TAB_RISE_MILLIS)) }
        WidgetTab(
          label = widgetLabel(item.key),
          open = item.key == edge.shown?.key,
          changed = tabs.changed(item.key, item.widget),
          belowEditor = belowEditor,
          onClick = { tabs.toggle(item.key, belowEditor) },
          modifier = Modifier
            .animateItem()
            .graphicsLayer { translationY = rise.value * size.height * fromBorder },
        )
      }
    }
  }
}

/**
 * One tab: a pill with an icon for its edge, the label, and a chevron while it's open or a yellow dot when the widget
 * changed since the user last saw it. An open tab is tinted cobalt.
 */
@Composable
private fun WidgetTab(
  label: String,
  open: Boolean,
  changed: Boolean,
  belowEditor: Boolean,
  onClick: () -> Unit,
  modifier: Modifier,
) {
  val colors = pipkinColors
  val content = if (open) colors.accent else colors.foregroundMuted
  val description = if (changed) stringResource(R.string.chat_widget_changed, label) else label
  val action = stringResource(if (open) R.string.chat_widget_close else R.string.chat_widget_open)
  Row(
    modifier = modifier
      .height(TAB_HEIGHT)
      .clip(CircleShape)
      .background(if (open) colors.accentSoft else colors.surface2)
      .clickable(onClickLabel = action, role = Role.Tab, onClick = onClick)
      .semantics {
        contentDescription = description
        selected = open
      }
      .padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    LucideIcon(
      icon = if (belowEditor) R.drawable.ic_lucide_panel_bottom else R.drawable.ic_lucide_panel_top,
      contentDescription = null,
      size = PipkinTokens.IconSize.xs,
      tint = content,
    )
    Text(
      text = label,
      modifier = Modifier.widthIn(max = TAB_LABEL_MAX_WIDTH),
      color = content,
      fontSize = 11.5.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    when {
      open -> LucideIcon(
        icon = if (belowEditor) R.drawable.ic_lucide_chevron_up else R.drawable.ic_lucide_chevron_down,
        contentDescription = null,
        size = PipkinTokens.IconSize.xs,
        tint = content,
      )

      changed -> Box(modifier = Modifier.size(7.dp).background(colors.dotWarning, CircleShape))
    }
  }
}

/**
 * Fades the ends of a tab strip or a panel where it scrolls past them, so a tab or a line cut off at the edge reads as
 * more to scroll to.
 */
private fun Modifier.fadeScrolledEdges(state: ScrollableState, vertical: Boolean): Modifier =
  graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
    drawContent()
    val length = if (vertical) size.height else size.width
    val fade = SCROLL_FADE.toPx().coerceAtMost(length / 2)
    val fadeEdge = { start: Float, colors: List<Color> ->
      val end = start + fade
      drawRect(
        brush = if (vertical) {
          Brush.verticalGradient(colors, startY = start, endY = end)
        } else {
          Brush.horizontalGradient(colors, startX = start, endX = end)
        },
        topLeft = if (vertical) Offset(0f, start) else Offset(start, 0f),
        size = if (vertical) Size(size.width, fade) else Size(fade, size.height),
        blendMode = BlendMode.DstIn,
      )
    }
    if (state.canScrollBackward) fadeEdge(0f, listOf(Color.Transparent, Color.Black))
    if (state.canScrollForward) fadeEdge(length - fade, listOf(Color.Black, Color.Transparent))
  }

/**
 * The open tab's widget inside the composer: above the text field for a widget above the editor, under the toolbar for
 * one below it. It grows open and folds closed, and switching tabs eases from one widget's height to the other's. The
 * user has seen the widget's lines for as long as they show. A [tight] panel keeps to a few lines.
 */
@Composable
internal fun WidgetPanelSlot(
  shown: KeyedWidget?,
  tabs: WidgetTabs,
  belowEditor: Boolean,
  tight: Boolean,
  onExpand: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  LaunchedEffect(shown?.key, shown?.widget) { shown?.let { tabs.see(it.key, it.widget) } }
  AnimatedContent(
    targetState = shown,
    modifier = modifier,
    contentKey = { it?.key },
    transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = true) },
    contentAlignment = if (belowEditor) Alignment.TopStart else Alignment.BottomStart,
    label = "widget panel",
  ) { item ->
    if (item != null) WidgetPanel(item, belowEditor, tight, onExpand = { onExpand(item.key) })
  }
}

/**
 * A widget's lines as Pi sent them, in monospace and wrapped like Pi's terminal UI, up to [PANEL_MAX_HEIGHT], or
 * [PANEL_TIGHT_MAX_HEIGHT] when [tight], and then scrolling. Unlike Pi's terminal UI, which shows the first 10 lines,
 * every line is here. Beside them at the top, a button opens the widget in a sheet.
 */
@Composable
private fun WidgetPanel(item: KeyedWidget, belowEditor: Boolean, tight: Boolean, onExpand: () -> Unit) {
  val colors = pipkinColors
  val text = remember(item.widget.lines, colors) { extensionWidgetText(item.widget.lines, colors) }
  val scroll = rememberScrollState()
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = if (belowEditor) PipkinTokens.Spacing.s8 else PipkinTokens.Spacing.s2)
      .clip(PANEL_SHAPE)
      .background(colors.surface0)
      .padding(start = 11.dp, top = PipkinTokens.Spacing.s4, end = PipkinTokens.Spacing.s4),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    Text(
      text = text,
      modifier = Modifier
        .weight(1f)
        .padding(top = PANEL_TEXT_TOP)
        .heightIn(max = if (tight) PANEL_TIGHT_MAX_HEIGHT else PANEL_MAX_HEIGHT)
        .fadeScrolledEdges(scroll, vertical = true)
        .verticalScroll(scroll)
        .padding(bottom = PipkinTokens.Spacing.s8),
      color = colors.foreground,
      fontFamily = PipkinFonts.mono,
      fontSize = PANEL_FONT_SIZE,
      lineHeight = PANEL_LINE_HEIGHT,
    )
    RoundIconButton(
      icon = R.drawable.ic_lucide_maximize_2,
      contentDescription = stringResource(R.string.chat_widget_expand),
      onClick = onExpand,
      modifier = Modifier.size(EXPAND_SIZE),
      tint = colors.foregroundMuted,
      iconSize = PipkinTokens.IconSize.sm,
    )
  }
}

/** The whole widget over the chat, for one too long for its panel: its label over every line, selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetSheet(item: KeyedWidget, tabs: WidgetTabs, onDismiss: () -> Unit) {
  val colors = pipkinColors
  val widget = item.widget
  val text = remember(widget.lines, colors) { extensionWidgetText(widget.lines, colors) }
  LaunchedEffect(widget) { tabs.see(item.key, widget) }
  PipkinSheet(onDismissRequest = onDismiss, maxHeightFraction = SHEET_MAX_HEIGHT) {
    Row(
      modifier = Modifier.padding(
        start = PipkinTokens.Spacing.s16,
        end = PipkinTokens.Spacing.s16,
        bottom = PipkinTokens.Spacing.s12,
      ),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      LucideIcon(
        icon = if (widget.belowEditor) R.drawable.ic_lucide_panel_bottom else R.drawable.ic_lucide_panel_top,
        contentDescription = null,
        size = PipkinTokens.IconSize.md,
        tint = colors.accent,
      )
      Text(
        text = widgetLabel(item.key),
        modifier = Modifier.weight(1f),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.lg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
      Text(
        text = text,
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s16, bottom = PipkinTokens.Spacing.s24),
        color = colors.foreground,
        fontFamily = PipkinFonts.mono,
        fontSize = PipkinTokens.FontSize.code,
        lineHeight = PipkinTokens.LineHeight.code,
      )
    }
  }
}

/**
 * A tab's label: the widget's key with dashes and underscores as spaces and its first letter capitalized, so
 * `plan-todos` reads "Plan todos". Pi gives a widget no title of its own, only the key its extension chose.
 */
internal fun widgetLabel(key: String): String =
  key.replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { it.titlecase() }.ifEmpty { key }
