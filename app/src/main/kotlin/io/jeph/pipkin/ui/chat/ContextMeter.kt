package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
 * Paseo's context window meter: a ring that fills as the context does, next to the send button.
 * A tap opens a tooltip with the percentage, the token counts and the session cost.
 */
@Composable
internal fun ContextMeter(usage: ContextUsage?) {
  val percent = usage?.percent?.roundToInt()
  var open by remember { mutableStateOf(false) }
  val description =
    if (percent == null) {
      stringResource(R.string.context_window_a11y_no_data)
    } else {
      stringResource(R.string.context_window_a11y, percent)
    }
  Box(
    modifier =
      Modifier
        .size(28.dp)
        .clip(CircleShape)
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.context_window_title)) { open = !open }
        .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    ContextRing(usage?.percent)
    if (open) {
      val gap = with(LocalDensity.current) { PipkinTokens.Spacing.s8.roundToPx() }
      Popup(
        popupPositionProvider = remember(gap) { AboveAnchor(gap) },
        onDismissRequest = { open = false },
        // Focusable, so a tap outside (the meter included) only closes the tooltip rather than also landing.
        properties = PopupProperties(focusable = true),
      ) {
        ContextDetails(usage, percent)
      }
    }
  }
}

/** A 16dp ring; the arc runs clockwise from twelve and turns amber at 70% and red past 90%. */
@Composable
private fun ContextRing(percent: Double?) {
  val colors = pipkinColors
  val progress = percent?.let { ringColor(it, colors) }
  Canvas(modifier = Modifier.size(PipkinTokens.IconSize.md)) {
    val stroke = 2.dp.toPx()
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

/** The tooltip body: Paseo's `ContextWindowDetails` with its title, as in the native tooltip. */
@Composable
private fun ContextDetails(usage: ContextUsage?, percent: Int?) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.xl)
  // Paseo's popover colour: a raised surface in dark mode, the page surface in light mode.
  val container = if (colors.isDark) colors.surface2 else colors.surface0
  Column(
    modifier =
      Modifier
        .shadow(8.dp, shape, ambientColor = SHADOW, spotColor = SHADOW)
        .background(container, shape)
        .border(1.dp, colors.borderAccent, shape)
        .padding(horizontal = PipkinTokens.Spacing.s8, vertical = PipkinTokens.Spacing.s4)
        .widthIn(min = 200.dp),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    Text(
      stringResource(R.string.context_window_title),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
    )
    val tokens = usage?.tokens
    val window = usage?.contextWindow
    if (percent != null && tokens != null && window != null) {
      Text(
        text = stringResource(R.string.context_window_used, percent),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        lineHeight = PipkinTokens.FontSize.base * 1.4f,
      )
      Detail(stringResource(R.string.context_window_tokens, formatTokens(tokens), formatTokens(window)))
    } else {
      Detail(stringResource(R.string.context_window_no_data))
      // Right after a compaction Pi knows the window but not the count until the next reply.
      window?.let { Detail(stringResource(R.string.session_context_unknown, formatTokens(it))) }
    }
    usage?.cost?.takeIf { it > 0 }?.let { Detail(stringResource(R.string.context_window_session_cost, formatCost(it))) }
  }
}

@Composable
private fun Detail(text: String) {
  Text(
    text = text,
    color = pipkinColors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.sm,
    lineHeight = PipkinTokens.FontSize.sm * 1.4f,
  )
}

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
