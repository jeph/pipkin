package io.jeph.pipkin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

/** How often the pot moves. Each step redraws the window, so it steps this often instead of every display frame. */
private const val FRAME_MILLIS = 100L

/** One rise of a wisp; the two wisps are half a cycle apart and the lid hops as each one leaves. */
private const val STEAM_CYCLE_MILLIS = 1_600L

/** The launcher icon's area around the pot, in its group's coordinates, with room for the steam to rise. */
private const val VIEW_LEFT = 85f
private const val VIEW_TOP = 62f
private const val VIEW_SIZE = 356f

/** Heavier than the launcher icon's 17 so the steam still shows at icon sizes. */
private const val STEAM_WIDTH = 30f

private const val LID_PIVOT_X = 243f
private const val LID_PIVOT_Y = 218f

/**
 * False where pots are composed but off screen: they then hold their current frame instead of redrawing the
 * window ten times a second for nothing.
 */
val LocalPotsSimmer = compositionLocalOf { true }

/**
 * Pipkin's loading indicator: the launcher icon's pot simmering, steam rising out of it while the lid hops as
 * each wisp leaves. Every pot on screen steps on the same [FRAME_MILLIS] boundaries, so they share frames
 * with each other. Pots hold still while [LocalPotsSimmer] is false.
 */
@Composable
fun SimmeringPot(
  modifier: Modifier = Modifier,
  size: Dp = PipkinTokens.IconSize.md,
  tint: Color = pipkinColors.foregroundMuted,
) {
  val icon = ImageVector.vectorResource(R.drawable.ic_launcher_foreground)
  val pot = remember(icon) { Pot(icon) }
  var step by remember { mutableLongStateOf(0L) }
  val simmer = LocalPotsSimmer.current
  LaunchedEffect(simmer) {
    if (!simmer) return@LaunchedEffect
    // Awaiting a frame rather than only delaying pauses the loop while the app is in the background.
    while (true) {
      val now = withFrameMillis { frameMillis ->
        step = frameMillis / FRAME_MILLIS
        frameMillis
      }
      delay(FRAME_MILLIS - now % FRAME_MILLIS)
    }
  }
  // Reading the step only while drawing redraws the pot without recomposing anything.
  Canvas(modifier.size(size)) { pot.draw(this, step * FRAME_MILLIS, tint) }
}

/** The launcher icon's parts as paths, and the scratch paths for drawing its steam. */
private class Pot(icon: ImageVector) {
  private val handle = icon.path("handle")
  private val legs = icon.path("legs")
  private val body = icon.path("body")
  private val lid = icon.path("lid")
  private val legStroke = Stroke(width = icon.vectorPath("legs").strokeLineWidth, cap = StrokeCap.Round)
  private val steamStroke = Stroke(width = STEAM_WIDTH, cap = StrokeCap.Round)
  private val wisps = listOf("steamLeft", "steamRight").map { name ->
    PathMeasure().apply { setPath(icon.path(name), forceClosed = false) }
  }
  private val wispSegment = Path()

  fun draw(scope: DrawScope, timeMillis: Long, tint: Color) = with(scope) {
    val scale = size.minDimension / VIEW_SIZE
    withTransform({
      scale(scale, scale, pivot = Offset.Zero)
      translate(-VIEW_LEFT, -VIEW_TOP)
    }) {
      wisps.forEachIndexed { index, wisp -> drawWisp(wisp, cyclePosition(timeMillis, index * 0.5f), tint) }
      drawPath(handle, tint)
      drawPath(legs, tint, style = legStroke)
      drawPath(body, tint)
      val hop = lidHop(timeMillis)
      translate(top = -10f * hop) {
        rotate(-3f * hop, pivot = Offset(LID_PIVOT_X, LID_PIVOT_Y)) { drawPath(lid, tint) }
      }
    }
  }

  /**
   * A wisp at [position] through its rise: it draws in from the pot upward, then clears from the bottom while
   * drifting up, swaying, and fading in and out.
   */
  private fun DrawScope.drawWisp(wisp: PathMeasure, position: Float, tint: Color) {
    val start = (2 * position - 1).coerceAtLeast(0f) * wisp.length
    val end = (2 * position).coerceAtMost(1f) * wisp.length
    wispSegment.reset()
    if (!wisp.getSegment(start, end, wispSegment, startWithMoveTo = true)) return
    val fade = (position / 0.2f).coerceIn(0f, 1f) * ((1 - position) / 0.3f).coerceIn(0f, 1f)
    val sway = 3f * sin(2 * PI.toFloat() * position)
    translate(left = sway, top = 20f - 44f * position) {
      drawPath(wispSegment, tint, alpha = fade, style = steamStroke)
    }
  }
}

/** Where a wisp is in its rise, from 0 to 1, starting [offset] of a cycle in. */
private fun cyclePosition(timeMillis: Long, offset: Float): Float =
  ((timeMillis % STEAM_CYCLE_MILLIS).toFloat() / STEAM_CYCLE_MILLIS + offset) % 1f

/** How far the lid is lifted, from 0 to 1: a short hop at the start of each half cycle, when a wisp leaves. */
private fun lidHop(timeMillis: Long): Float {
  val half = STEAM_CYCLE_MILLIS / 2
  val position = (timeMillis % half).toFloat() / half
  return if (position < 0.18f) sin(PI.toFloat() * position / 0.18f) else 0f
}

private fun ImageVector.vectorPath(name: String): VectorPath = root.group(name).first() as VectorPath

private fun ImageVector.path(name: String): Path {
  val vector = vectorPath(name)
  return vector.pathData.toPath().apply { fillType = vector.pathFillType }
}

private fun VectorGroup.group(name: String): VectorGroup = asSequence()
  .filterIsInstance<VectorGroup>()
  .flatMap { sequenceOf(it) + it.asSequence().filterIsInstance<VectorGroup>() }
  .first { it.name == name }
