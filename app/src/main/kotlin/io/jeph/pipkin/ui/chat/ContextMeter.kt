package io.jeph.pipkin.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ContextUsage
import io.jeph.pipkin.ui.settings.formatTokens
import io.jeph.pipkin.ui.theme.PipkinColors
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.math.roundToInt

/**
 * The context window meter: a ring that fills as the context does, next to the send button.
 * A tap opens a popover with the percentage, a bar, the token counts and the session cost; the chat closes it
 * on any press (see [closeContextDetailsOnPress]), and so does back.
 */
@Composable
internal fun ContextMeter(usage: ContextUsage?, open: Boolean, onOpenChange: (Boolean) -> Unit) {
  val percent = usage?.percent?.roundToInt()
  // The chat closes the tooltip on the press that starts a tap on the meter, before the click lands;
  // that click must then leave it closed rather than open it again.
  var pressedWhileOpen by remember { mutableStateOf(false) }
  val currentOpen by rememberUpdatedState(open)
  val description =
    if (percent == null) {
      stringResource(R.string.context_window_a11y_no_data)
    } else {
      stringResource(R.string.context_window_a11y, percent)
    }
  Box(
    modifier =
      Modifier
        .size(30.dp)
        .clip(CircleShape)
        .pointerInput(Unit) {
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pressedWhileOpen = currentOpen
          }
        }
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.context_window_title)) {
          if (pressedWhileOpen) pressedWhileOpen = false else onOpenChange(!open)
        }
        .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    ContextRing(usage?.percent)
    if (open) {
      BackHandler { onOpenChange(false) }
      val gap = with(LocalDensity.current) { PipkinTokens.Spacing.s8.roundToPx() }
      // Not focusable, so the keyboard stays up; the chat closes it on outside presses instead of the popup.
      Popup(
        popupPositionProvider = remember(gap) { AboveAnchor(gap) },
        onDismissRequest = { onOpenChange(false) },
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
      ) {
        ContextDetails(usage, percent)
      }
    }
  }
}

/** Closes the context tooltip on any press inside this element, whatever handles the press. */
internal fun Modifier.closeContextDetailsOnPress(controller: ChatController): Modifier = pointerInput(controller) {
  awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
    controller.contextDetailsOpen = false
  }
}

/** A 23dp ring; the arc runs clockwise from twelve and turns amber at 70% and red past 90%. */
@Composable
private fun ContextRing(percent: Double?) {
  val colors = pipkinColors
  val progress = percent?.let { ringColor(it, colors) }
  Canvas(modifier = Modifier.size(RING_SIZE)) {
    val stroke = RING_STROKE.toPx()
    val inset = stroke / 2
    val arcSize = Size(size.width - stroke, size.height - stroke)
    drawCircle(colors.surface3, radius = arcSize.minDimension / 2, style = Stroke(stroke))
    val sweep = (percent ?: 0.0).coerceIn(0.0, 100.0).toFloat() * 360f / 100f
    if (progress != null && sweep > 0f) {
      drawArc(
        color = progress,
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = arcSize,
        style = Stroke(stroke, cap = StrokeCap.Round),
      )
    }
  }
}

private fun ringColor(percent: Double, colors: PipkinColors): Color = when {
  percent > 90 -> colors.destructive
  percent >= 70 -> colors.dotWarning
  else -> colors.foregroundMuted
}

/** The popover: its title, the share of the window used over a bar in the ring's colour, then the details. */
@Composable
private fun ContextDetails(usage: ContextUsage?, percent: Int?) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(22.dp)
  Column(
    modifier =
      Modifier
        .shadow(8.dp, shape, ambientColor = SHADOW, spotColor = SHADOW)
        .background(colors.surface2, shape)
        .padding(horizontal = PipkinTokens.Spacing.s16, vertical = 14.dp)
        .widthIn(min = 220.dp, max = 280.dp),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    Text(
      stringResource(R.string.context_window_title),
      color = colors.foreground,
      fontSize = 13.5.sp,
      fontWeight = FontWeight.ExtraBold,
    )
    val tokens = usage?.tokens
    val window = usage?.contextWindow
    if (percent != null && tokens != null && window != null) {
      Text(
        text = stringResource(R.string.context_window_used, percent),
        color = colors.foreground,
        fontSize = DETAIL_SIZE,
        lineHeight = DETAIL_LINE_HEIGHT,
      )
      UsageBar(usage.percent ?: 0.0, ringColor(usage.percent ?: 0.0, colors))
      Detail(stringResource(R.string.context_window_tokens, formatTokens(tokens), formatTokens(window)))
    } else {
      Detail(stringResource(R.string.context_window_no_data))
      // Right after a compaction Pi knows the window but not the count until the next reply.
      window?.let { Detail(stringResource(R.string.session_context_unknown, formatTokens(it))) }
    }
    usage?.cost?.takeIf { it > 0 }?.let { Detail(stringResource(R.string.context_window_session_cost, formatCost(it))) }
  }
}

/** An 8dp bar filled to [percent] in [fill]. */
@Composable
private fun UsageBar(percent: Double, fill: Color) {
  val track = pipkinColors.surface3
  Canvas(modifier = Modifier.fillMaxWidth().padding(vertical = PipkinTokens.Spacing.s4).height(BAR_HEIGHT)) {
    val radius = CornerRadius(size.height / 2)
    drawRoundRect(track, cornerRadius = radius)
    val width = size.width * (percent.coerceIn(0.0, 100.0) / 100.0).toFloat()
    if (width <= 0f) return@Canvas
    // Mirrored in right-to-left layouts, so the bar fills from the start like the text reads.
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    drawRoundRect(fill, topLeft = Offset(left, 0f), size = Size(width, size.height), cornerRadius = radius)
  }
}

@Composable
private fun Detail(text: String) {
  Text(
    text = text,
    color = pipkinColors.foregroundMuted,
    fontSize = DETAIL_SIZE,
    lineHeight = DETAIL_LINE_HEIGHT,
  )
}

private val RING_SIZE = 23.dp
private val RING_STROKE = 2.6.dp
private val BAR_HEIGHT = 8.dp
private val DETAIL_SIZE = 13.sp
private val DETAIL_LINE_HEIGHT = 19.5.sp

/** Centred above the anchor and kept [gap] from it, shifted to stay on screen; below when there's no room above. */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val maxX = (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)
    val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(gap, maxX)
    val above = anchorBounds.top - gap - popupContentSize.height
    return IntOffset(x, if (above >= gap) above else anchorBounds.bottom + gap)
  }
}

/** Paseo's `shadow.md`: black at 20%. */
private val SHADOW = Color.Black.copy(alpha = 0.2f)
