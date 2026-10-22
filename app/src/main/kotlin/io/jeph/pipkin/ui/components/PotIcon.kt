package io.jeph.pipkin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.ui.theme.pipkinColors

/**
 * The headers' pot: a still, 24-unit pot in the accent with a prompt on its body and two muted wisps of steam. It
 * stays still where [SimmeringPot] would move, since it's a button rather than a sign of work.
 */
@Composable
fun PotIcon(modifier: Modifier = Modifier, size: Dp = 22.dp) {
  val colors = pipkinColors
  Canvas(modifier.size(size)) {
    scale(this.size.minDimension / VIEW_SIZE, pivot = Offset.Zero) {
      drawPath(STEAM, colors.foregroundMuted, style = STEAM_STROKE)
      drawPath(BODY, colors.accent)
      drawPath(HANDLE, colors.accent, style = HANDLE_STROKE)
      drawPath(LEGS, colors.accent, style = LEG_STROKE)
      drawPath(PROMPT, colors.accentForeground, style = PROMPT_STROKE)
    }
  }
}

private const val VIEW_SIZE = 24f

private fun path(data: String) = PathParser().parsePathString(data).toPath()

private val STEAM = path("M9 2.5c-.8.9-.8 1.9 0 2.8M13 2.5c-.8.9-.8 1.9 0 2.8")
private val BODY = path("M3.2 8.2h15.6l-1.3 7.6c-.3 1.6-1.4 2.4-2.9 2.4H7.4c-1.5 0-2.6-.8-2.9-2.4z")
private val HANDLE = path("M18.6 9.6h2.6")
private val LEGS = path("M7.6 18.2l-.9 2.6M14.4 18.2l.9 2.6")
private val PROMPT = path("M7.4 11.3l1.8 1.5-1.8 1.5M10.8 14.3h3")

private val STEAM_STROKE = Stroke(width = 1.6f, cap = StrokeCap.Round)
private val HANDLE_STROKE = Stroke(width = 2f, cap = StrokeCap.Round)
private val LEG_STROKE = Stroke(width = 1.8f, cap = StrokeCap.Round)
private val PROMPT_STROKE = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
