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

/** One loop: the pot shakes, a wisp leaves, the pot rests, and a second wisp leaves with a hop of the lid. */
private const val LOOP_MILLIS = 2_000L

/** When each wisp leaves the pot: one as the shake ends, the other halfway round the loop with the lid's hop. */
private val WISP_LEAVE_MILLIS = longArrayOf(500L, 1_500L)

/** The launcher icon's area around the pot, in its group's coordinates, with room for the steam to rise. */
private const val VIEW_LEFT = 85f
private const val VIEW_TOP = 62f
private const val VIEW_SIZE = 356f

/**
 * About a pixel per 10 units at icon sizes, so the steam is drawn heavier than the launcher icon's 17 and rises
 * and sways by whole pixels; smaller moves vanish into antialiasing.
 */
private const val STEAM_WIDTH = 38f
private const val STEAM_RISE = 56f
private const val STEAM_SWAY = 5f

private val LID_PIVOT = Offset(243f, 218f)

/** The middle of the pot's feet, which it rocks on. */
private val FEET = Offset(243f, 397f)

/** Where the pot and its lid are on one frame: tilts in degrees clockwise, the lid's lift in icon units. */
private class Pose(val potTilt: Float = 0f, val lidLift: Float = 0f, val lidTilt: Float = 0f)

private val REST = Pose()

/**
 * The shake that opens each loop, one pose per frame: the pot rocks on its feet, swinging less each time, while
 * the lid jumps and rattles against it. Whole-pot moves of a pixel or two still show at icon sizes.
 */
private val SHAKE = listOf(
  Pose(potTilt = -5f, lidLift = 10f, lidTilt = 3f),
  Pose(potTilt = 5f, lidLift = 16f, lidTilt = -3f),
  Pose(potTilt = -4f, lidLift = 8f, lidTilt = 2f),
  Pose(potTilt = 4f, lidLift = 14f, lidTilt = -2f),
  Pose(potTilt = -2f, lidLift = 4f, lidTilt = 1f),
)

/** The lid's hop as the second wisp leaves. */
private val HOP = Pose(lidLift = 10f, lidTilt = -3f)
private val HOP_FRAME = (WISP_LEAVE_MILLIS[1] / FRAME_MILLIS).toInt()

/**
 * False where pots are composed but off screen: they then hold their current frame instead of redrawing the
 * window ten times a second for nothing.
 */
val LocalPotsSimmer = compositionLocalOf { true }

/**
 * Pipkin's loading indicator: the launcher icon's pot simmering. It shakes on its feet with the lid rattling,
 * lets out a wisp of steam, rests, and lets out another as the lid hops, every [LOOP_MILLIS]. Every pot on screen
 * steps on the same [FRAME_MILLIS] boundaries, so they share frames with each other. Pots hold still while
 * [LocalPotsSimmer] is false.
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
    val pose = poseAt(timeMillis)
    withTransform({
      scale(scale, scale, pivot = Offset.Zero)
      translate(-VIEW_LEFT, -VIEW_TOP)
    }) {
      wisps.forEachIndexed { index, wisp -> drawWisp(wisp, wispPosition(timeMillis, WISP_LEAVE_MILLIS[index]), tint) }
      rotate(pose.potTilt, pivot = FEET) {
        drawPath(handle, tint)
        drawPath(legs, tint, style = legStroke)
        drawPath(body, tint)
        translate(top = -pose.lidLift) {
          rotate(pose.lidTilt, pivot = LID_PIVOT) { drawPath(lid, tint) }
        }
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
    val sway = STEAM_SWAY * sin(2 * PI.toFloat() * position)
    translate(left = sway, top = 20f - STEAM_RISE * position) {
      drawPath(wispSegment, tint, alpha = fade, style = steamStroke)
    }
  }
}

/** Where a wisp that leaves the pot [leaveMillis] into each loop is in its rise, from 0 to 1. */
private fun wispPosition(timeMillis: Long, leaveMillis: Long): Float =
  Math.floorMod(timeMillis - leaveMillis, LOOP_MILLIS).toFloat() / LOOP_MILLIS

private fun poseAt(timeMillis: Long): Pose {
  val frame = ((timeMillis % LOOP_MILLIS) / FRAME_MILLIS).toInt()
  return when {
    frame < SHAKE.size -> SHAKE[frame]
    frame == HOP_FRAME -> HOP
    else -> REST
  }
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
