package io.jeph.pipkin.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.host.TermuxWaker
import io.jeph.pipkin.ui.components.CodeBox
import io.jeph.pipkin.ui.components.CodeText
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/**
 * The process banner at the top of the stream: starting, installing Pi, or failed with Retry. [onOpenOnboarding] opens
 * setup at the step a failure needs.
 */
@Composable
internal fun StatusBanner(
  state: AgentProcessState,
  onRetry: () -> Unit,
  onOpenOnboarding: () -> Unit,
  modifier: Modifier = Modifier,
) {
  when (state) {
    AgentProcessState.Starting -> StartingBanner(modifier)

    is AgentProcessState.Installing -> BannerCard(modifier) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        SimmeringPot(size = PipkinTokens.IconSize.lg)
        BannerTitle(stringResource(R.string.chat_installing))
      }
      state.progressLine?.let {
        Text(
          text = it,
          modifier = Modifier.fillMaxWidth(),
          color = pipkinColors.foregroundMuted,
          fontFamily = PipkinFonts.mono,
          fontSize = 11.5.sp,
          lineHeight = PipkinTokens.LineHeight.code,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    is AgentProcessState.Failed -> FailedBanner(state, onRetry, onOpenOnboarding, modifier)

    AgentProcessState.Ready, is AgentProcessState.Exited -> Unit
  }
}

/** Starting is the chat's only content while a session opens, so the pot sits large over the word. */
@Composable
private fun StartingBanner(modifier: Modifier) {
  BannerCard(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    SimmeringPot(size = 32.dp, modifier = Modifier.padding(top = PipkinTokens.Spacing.s12))
    Text(
      text = stringResource(R.string.chat_starting),
      modifier = Modifier.padding(bottom = PipkinTokens.Spacing.s12),
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.title,
      fontWeight = FontWeight.ExtraBold,
    )
  }
}

/**
 * Pi could not start. A Termux setup failure offers setup, which opens at the step that fixes it; an unreachable Play
 * helper offers Open Termux, which starts the helper there and switches back to Pipkin before retrying, and setup for
 * when Termux says the helper can't start. The first action, the likeliest fix, is filled.
 */
@Composable
private fun FailedBanner(
  state: AgentProcessState.Failed,
  onRetry: () -> Unit,
  onOpenOnboarding: () -> Unit,
  modifier: Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val failure = state.failure
  val openTermux = failure?.needsTermuxOpen == true
  val setUp = failure?.fixedInTermux == true
  BannerCard(modifier) {
    AlertTitle(R.drawable.ic_lucide_circle_alert, state.message, error = true)
    if (openTermux) BannerText(stringResource(R.string.chat_open_termux_hint))
    // Open Termux, Set up Termux and Retry don't fit on one line at large font sizes.
    FlowRow(
      modifier = Modifier.padding(top = PipkinTokens.Spacing.s4),
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
    ) {
      if (openTermux) {
        BannerAction(stringResource(R.string.chat_open_termux), filled = true) {
          scope.launch { if (TermuxWaker.wake(context, asked = true)) onRetry() }
        }
      }
      if (setUp) {
        BannerAction(
          stringResource(R.string.chat_set_up_termux),
          filled = !openTermux,
          onClick = onOpenOnboarding,
        )
      }
      BannerAction(stringResource(R.string.chat_retry), filled = !openTermux && !setUp, onClick = onRetry)
    }
  }
}

/** Pi exited unasked: the exit code, an expandable stderr tail and Restart. */
@Composable
internal fun ExitBannerCard(item: TimelineItem.ExitBanner, onRestart: () -> Unit, modifier: Modifier = Modifier) {
  var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
  BannerCard(modifier) {
    AlertTitle(
      R.drawable.ic_lucide_circle_x,
      item.code?.let { stringResource(R.string.chat_exit_code, it) } ?: stringResource(R.string.chat_agent_stopped),
      error = false,
    )
    if (expanded) {
      SelectionContainer {
        CodeBox(background = pipkinColors.surface0, radius = 14.dp) { CodeText(item.stderr.trimEnd()) }
      }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      if (item.stderr.isNotBlank()) {
        BannerAction(
          stringResource(if (expanded) R.string.chat_hide_details else R.string.chat_show_details),
          filled = false,
        ) { expanded = !expanded }
      }
      Spacer(Modifier.weight(1f))
      BannerAction(
        stringResource(R.string.chat_restart),
        filled = true,
        icon = R.drawable.ic_lucide_refresh_cw,
        onClick = onRestart,
      )
    }
  }
}

/** The banner's first line: an icon in the error colour, then [text], itself red when [error]. */
@Composable
private fun AlertTitle(@DrawableRes icon: Int, text: String, error: Boolean) {
  val colors = pipkinColors
  Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
    LucideIcon(
      icon = icon,
      contentDescription = null,
      modifier = Modifier.padding(top = 2.dp),
      tint = colors.destructive,
    )
    Text(
      text = text,
      modifier = Modifier.weight(1f),
      color = if (error) colors.destructive else colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
      fontWeight = FontWeight.ExtraBold,
      lineHeight = 20.sp,
    )
  }
}

@Composable
private fun BannerTitle(text: String) {
  Text(
    text = text,
    color = pipkinColors.foreground,
    fontSize = PipkinTokens.FontSize.base,
    fontWeight = FontWeight.ExtraBold,
  )
}

@Composable
private fun BannerText(text: String) {
  Text(text = text, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.meta, lineHeight = 19.5.sp)
}

/** A banner's button: a cobalt pill when [filled], otherwise cobalt text. */
@Composable
private fun BannerAction(label: String, filled: Boolean, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
  val colors = pipkinColors
  val content = if (filled) colors.accentForeground else colors.accent
  Row(
    modifier = Modifier
      .heightIn(min = 36.dp)
      .clip(CircleShape)
      .then(if (filled) Modifier.background(colors.accent) else Modifier)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = PipkinTokens.Spacing.s12),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    if (icon !=
      null
    ) {
      LucideIcon(icon = icon, contentDescription = null, size = PipkinTokens.IconSize.sm, tint = content)
    }
    Text(text = label, color = content, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun BannerCard(
  modifier: Modifier,
  horizontalAlignment: Alignment.Horizontal = Alignment.Start,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(vertical = PipkinTokens.Spacing.s6)
        .clip(BannerShape)
        .background(pipkinColors.surface1)
        .padding(
          start = PipkinTokens.Spacing.s12,
          top = PipkinTokens.Spacing.s12,
          end = PipkinTokens.Spacing.s12,
          bottom = 9.dp,
        ),
    horizontalAlignment = horizontalAlignment,
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
    content = content,
  )
}

private val BannerShape = RoundedCornerShape(20.dp)
