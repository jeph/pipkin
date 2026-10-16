package io.jeph.pipkin.ui.onboarding

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** A step's icon on a rounded tile; it turns into an accent check, with a small bounce, once the step is [done]. */
@Composable
internal fun IconTile(@DrawableRes icon: Int, done: Boolean, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.xxl + PipkinTokens.Spacing.s8)
  AnimatedContent(
    targetState = done,
    modifier = modifier.size(TILE_SIZE),
    transitionSpec = {
      (
        fadeIn(
          tween(FADE_MILLIS),
        ) + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.6f)
        )
        .togetherWith(fadeOut(tween(FADE_MILLIS / 2)))
    },
    label = "step icon",
  ) { isDone ->
    val tile = Modifier.fillMaxSize()
    if (isDone) {
      Box(tile.background(colors.accent, shape), contentAlignment = Alignment.Center) {
        LucideIcon(
          icon = R.drawable.ic_lucide_check,
          contentDescription = stringResource(R.string.onboarding_status_done),
          size = ICON_SIZE,
          tint = colors.accentForeground,
        )
      }
    } else {
      Box(
        tile.background(colors.surface1, shape).border(1.dp, colors.border, shape),
        contentAlignment = Alignment.Center,
      ) {
        LucideIcon(icon = icon, contentDescription = null, size = ICON_SIZE, tint = colors.foreground)
      }
    }
  }
}

/** Where onboarding is: a dot per step, the current one stretched, finished ones in accent. */
@Composable
internal fun ProgressDots(done: List<Boolean>, current: Int, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  val description = stringResource(R.string.onboarding_progress, current + 1, done.size)
  Row(
    modifier = modifier.clearAndSetSemantics { contentDescription = description },
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    done.forEachIndexed { index, isDone ->
      val width by animateDpAsState(if (index == current) DOT_CURRENT_WIDTH else DOT_SIZE, label = "dot width")
      val color by animateColorAsState(
        when {
          index == current -> colors.accent
          isDone -> colors.accent.copy(alpha = DONE_DOT_ALPHA)
          else -> colors.borderAccent
        },
        label = "dot color",
      )
      Box(Modifier.size(width, DOT_SIZE).background(color, CircleShape))
    }
  }
}

/**
 * A small looping terminal that shows the setup command's paste: long-press, Paste, the command appears, and
 * Termux reports back. Everything moves in the draw and layer phases, so the loop never recomposes. With animations
 * off in Android's settings, it holds the finished frame.
 */
@Composable
internal fun PasteDemo(modifier: Modifier = Modifier) {
  val colors = pipkinColors
  val time = rememberDemoTime()
  val shape = RoundedCornerShape(PipkinTokens.Radius.xxl)
  val mono = PipkinTokens.FontSize.code
  val description = stringResource(R.string.onboarding_setup_body)
  Column(
    modifier = modifier
      .widthIn(max = DEMO_WIDTH)
      .fillMaxWidth()
      .semantics { contentDescription = description }
      .graphicsLayer { alpha = fade(time.value, FADE_OUT_AT, CYCLE_MILLIS, from = 1f, to = 0f) }
      .background(colors.surface1, shape)
      .border(1.dp, colors.border, shape)
      // The extra room on top is where the Paste bubble pops up.
      .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s16, bottom = PipkinTokens.Spacing.s16)
      .padding(top = PipkinTokens.Spacing.s16 + BUBBLE_ROOM),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text = PROMPT, color = colors.foregroundMuted, fontFamily = FontFamily.Monospace, fontSize = mono)
      Box(contentAlignment = Alignment.CenterStart) {
        PressTarget(time)
        Text(
          text = COMMAND,
          modifier = Modifier.graphicsLayer { alpha = fade(time.value, PASTE_AT, PASTE_AT + STEP_MILLIS) },
          color = colors.foreground,
          fontFamily = FontFamily.Monospace,
          fontSize = mono,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Text(
      text = stringResource(R.string.onboarding_demo_installing),
      modifier = Modifier.graphicsLayer { alpha = fade(time.value, RUN_AT, RUN_AT + STEP_MILLIS) },
      color = colors.foregroundMuted,
      fontFamily = FontFamily.Monospace,
      fontSize = mono,
    )
    DoneLine(time)
  }
}

/** Termux's last line in the demo: a check and "Pipkin setup done". */
@Composable
private fun DoneLine(time: State<Float>) {
  val colors = pipkinColors
  Row(
    modifier = Modifier.graphicsLayer { alpha = fade(time.value, DONE_AT, DONE_AT + STEP_MILLIS) },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    LucideIcon(
      icon = R.drawable.ic_lucide_check,
      contentDescription = null,
      size = PipkinTokens.IconSize.sm,
      tint = colors.accent,
    )
    Text(
      text = stringResource(R.string.onboarding_demo_done),
      color = colors.foreground,
      fontFamily = FontFamily.Monospace,
      fontSize = PipkinTokens.FontSize.code,
    )
  }
}

/** The blinking cursor where the finger long-presses, and the Paste bubble that pops up over it. */
@Composable
private fun PressTarget(time: State<Float>) {
  val colors = pipkinColors
  Box(
    modifier = Modifier
      .size(CURSOR_WIDTH, CURSOR_HEIGHT)
      .drawBehind {
        val t = time.value
        // The finger: a soft circle that grows while held, then lifts.
        val press = fade(t, PRESS_AT, PRESS_AT + PRESS_MILLIS)
        val lift = fade(t, PASTE_AT, PASTE_AT + STEP_MILLIS, from = 1f, to = 0f)
        if (press > 0f && lift > 0f) {
          drawCircle(
            color = colors.accent.copy(alpha = PRESS_ALPHA * lift),
            radius = (PRESS_MIN_RADIUS + (PRESS_MAX_RADIUS - PRESS_MIN_RADIUS) * press).toPx(),
          )
        }
        // The cursor blinks until the command lands in front of it.
        val blinkOn = (t / BLINK_MILLIS).toInt() % 2 == 0
        if (t < PASTE_AT && blinkOn) drawRect(colors.foreground, size = Size(size.width, size.height))
      },
  ) {
    Text(
      text = stringResource(R.string.onboarding_demo_paste),
      modifier = Modifier
        .wrapContentSize(unbounded = true)
        .offset(y = BUBBLE_OFFSET)
        .graphicsLayer {
          val t = time.value
          val shown = fade(t, BUBBLE_AT, BUBBLE_AT + STEP_MILLIS) * fade(t, PASTE_AT, PASTE_AT + STEP_MILLIS, 1f, 0f)
          alpha = shown
          // A tap on the bubble presses it in just before the paste.
          val tapped = if (t in (PASTE_AT - TAP_MILLIS)..PASTE_AT) TAP_SCALE else 1f
          scaleX = (BUBBLE_START_SCALE + (1f - BUBBLE_START_SCALE) * shown) * tapped
          scaleY = scaleX
        }
        .background(colors.surface3, RoundedCornerShape(PipkinTokens.Radius.md))
        .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s4),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.sm,
    )
  }
}

/** Milliseconds into the demo's loop; fixed on the finished frame when animations are off. */
@Composable
private fun rememberDemoTime(): State<Float> {
  val context = LocalContext.current
  val animationsOff = remember(context) {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
  }
  if (animationsOff) return remember { mutableFloatStateOf(STILL_AT) }
  return rememberInfiniteTransition(label = "paste demo").animateFloat(
    initialValue = 0f,
    targetValue = CYCLE_MILLIS,
    animationSpec = infiniteRepeatable(tween(CYCLE_MILLIS.toInt(), easing = LinearEasing)),
    label = "paste demo time",
  )
}

/** Goes from [from] to [to] as [t] moves from [start] to [end], clamped on either side. */
private fun fade(t: Float, start: Float, end: Float, from: Float = 0f, to: Float = 1f): Float {
  val progress = ((t - start) / (end - start)).coerceIn(0f, 1f)
  return from + (to - from) * progress
}

private val TILE_SIZE = 88.dp
private val ICON_SIZE = 40.dp
private const val FADE_MILLIS = 240
private val DOT_SIZE = 6.dp
private val DOT_CURRENT_WIDTH = 20.dp
private const val DONE_DOT_ALPHA = 0.45f

private val DEMO_WIDTH = 300.dp
private val BUBBLE_ROOM = 24.dp
private const val PROMPT = "~ $ "
private const val COMMAND = "curl -fsS …/setup.sh | bash"
private val CURSOR_WIDTH = 7.dp
private val CURSOR_HEIGHT = 14.dp
private val BUBBLE_OFFSET = (-28).dp
private val PRESS_MIN_RADIUS = 6.dp
private val PRESS_MAX_RADIUS = 18.dp
private const val PRESS_ALPHA = 0.3f
private const val BUBBLE_START_SCALE = 0.8f
private const val TAP_SCALE = 0.92f

// The loop, in milliseconds: press, bubble, tap and paste, Termux runs it, done, hold, fade out.
private const val CYCLE_MILLIS = 6_500f
private const val PRESS_AT = 500f
private const val PRESS_MILLIS = 700f
private const val BUBBLE_AT = 1_100f
private const val TAP_MILLIS = 200f
private const val PASTE_AT = 2_100f
private const val RUN_AT = 2_900f
private const val DONE_AT = 4_000f
private const val FADE_OUT_AT = 6_000f
private const val STEP_MILLIS = 200f
private const val BLINK_MILLIS = 500f
private const val STILL_AT = 5_000f
