package io.jeph.pipkin.ui.chat

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Returns a function that puts plain text on the clipboard; Android shows its own copied confirmation. */
@Composable
internal fun rememberCopyText(): (String) -> Unit {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  return remember(clipboard, scope) {
    { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Pipkin", text))) } }
  }
}

/**
 * A 28dp copy button whose icon turns into a check for [COPIED] after each copy, as Paseo's turn copy
 * button does. It never takes focus, so the keyboard stays up.
 */
@Composable
internal fun CopyButton(text: String, contentDescription: String, tint: Color, iconSize: Dp) {
  val copy = rememberCopyText()
  // Counts copies, so copying again while the check shows restarts its timer.
  var copies by remember { mutableIntStateOf(0) }
  var showCheck by remember { mutableStateOf(false) }
  LaunchedEffect(copies) {
    if (copies == 0) return@LaunchedEffect
    showCheck = true
    delay(COPIED)
    showCheck = false
  }
  Box(
    modifier = Modifier
      .size(28.dp)
      .clip(CircleShape)
      .clickable {
        copy(text)
        copies++
      },
    contentAlignment = Alignment.Center,
  ) {
    AnimatedContent(
      targetState = showCheck,
      transitionSpec = {
        (fadeIn(tween(ICON_SWAP_MILLIS)) + scaleIn(tween(ICON_SWAP_MILLIS), initialScale = 0.5f))
          .togetherWith(fadeOut(tween(ICON_SWAP_MILLIS)) + scaleOut(tween(ICON_SWAP_MILLIS), targetScale = 0.5f))
      },
      label = "copyIcon",
    ) { check ->
      LucideIcon(
        icon = if (check) R.drawable.ic_lucide_check else R.drawable.ic_lucide_copy,
        contentDescription = if (check) stringResource(R.string.chat_copied) else contentDescription,
        size = iconSize,
        tint = tint,
      )
    }
  }
}

private val COPIED = 1.5.seconds
private const val ICON_SWAP_MILLIS = 150
