package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.termux.TermuxSetup
import io.jeph.pipkin.ui.components.CodeBox
import io.jeph.pipkin.ui.components.CodeText
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.Spinner
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** The process banner at the top of the stream: starting, installing Pi, or failed with Retry. */
@Composable
internal fun StatusBanner(state: AgentProcessState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  when (state) {
    AgentProcessState.Starting -> BannerLine(stringResource(R.string.chat_starting), modifier)

    is AgentProcessState.Installing -> Column(modifier = modifier) {
      BannerLine(stringResource(R.string.chat_installing))
      state.progressLine?.let {
        Text(
          text = it,
          modifier = Modifier.fillMaxWidth(),
          color = pipkinColors.foregroundMuted,
          fontFamily = FontFamily.Monospace,
          fontSize = PipkinTokens.FontSize.code,
          lineHeight = PipkinTokens.LineHeight.code,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    is AgentProcessState.Failed -> FailedBanner(state, onRetry, modifier)

    AgentProcessState.Ready, is AgentProcessState.Exited -> Unit
  }
}

@Composable
private fun BannerLine(text: String, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8, Alignment.CenterHorizontally),
  ) {
    Spinner(size = PipkinTokens.IconSize.sm)
    Text(text = text, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.meta)
  }
}

/** Pi could not start; a Termux setup failure also offers its fix command. */
@Composable
private fun FailedBanner(state: AgentProcessState.Failed, onRetry: () -> Unit, modifier: Modifier) {
  val context = LocalContext.current
  val command = state.failure?.command
  BannerCard(modifier) {
    AlertLine(state.message)
    if (state.failure?.needsSetup == true) {
      Text(
        text = stringResource(R.string.chat_setup_hint),
        color = pipkinColors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.meta,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
      TextButton(onClick = onRetry) { Text(text = stringResource(R.string.chat_retry)) }
      if (command != null) {
        TextButton(onClick = { TermuxSetup.copyAndOpen(context, command) }) {
          Text(text = stringResource(R.string.chat_copy_command))
        }
      }
    }
  }
}

/** Pi exited unasked: the exit code, an expandable stderr tail and Restart. */
@Composable
internal fun ExitBannerCard(item: TimelineItem.ExitBanner, onRestart: () -> Unit, modifier: Modifier = Modifier) {
  var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
  BannerCard(modifier) {
    AlertLine(
      item.code?.let { stringResource(R.string.chat_exit_code, it) } ?: stringResource(R.string.chat_agent_stopped),
    )
    if (item.stderr.isNotBlank()) {
      Text(
        text = stringResource(if (expanded) R.string.chat_hide_details else R.string.chat_show_details),
        modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = PipkinTokens.Spacing.s4),
        color = pipkinColors.accent,
        fontSize = PipkinTokens.FontSize.meta,
      )
      if (expanded) SelectionContainer { CodeBox { CodeText(item.stderr.trimEnd()) } }
    }
    TextButton(onClick = onRestart) { Text(text = stringResource(R.string.chat_restart)) }
  }
}

@Composable
private fun AlertLine(text: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    LucideIcon(
      icon = R.drawable.ic_lucide_circle_alert,
      contentDescription = null,
      modifier = Modifier.padding(top = PipkinTokens.Spacing.s2),
      tint = pipkinColors.destructive,
    )
    Text(
      text = text,
      modifier = Modifier.weight(1f),
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.base,
    )
  }
}

@Composable
private fun BannerCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(vertical = PipkinTokens.Spacing.s8)
        .clip(RoundedCornerShape(PipkinTokens.Radius.lg))
        .background(pipkinColors.surface2)
        .padding(start = PipkinTokens.Spacing.s12, top = PipkinTokens.Spacing.s12, end = PipkinTokens.Spacing.s12),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
    content = content,
  )
}
