package io.jeph.pipkin.ui.subagents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.SubagentRun
import io.jeph.pipkin.agent.SubagentStatus
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.ui.chat.ChatController
import io.jeph.pipkin.ui.chat.PendingDialogCard
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.atMostHalfTall
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.CloseSplitButton
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.TabsButton
import io.jeph.pipkin.ui.workspace.rememberWorkspaceTabs
import kotlinx.coroutines.launch

private val TRAY_SHAPE = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val SEND_SIZE = 40.dp
private val SEND_ICON_SIZE = 18.dp

/**
 * The subagent the chat's `subagent` tool call [toolCallId] started, in place of the chat in its pane: live while it
 * runs, read only once it's done. The chat's question card shows under the transcript, answered through the chat's
 * [controller], since the subagent's questions are the chat's. While it is active, a box steers it and Stop ends it.
 * [chatTitle] is the chat's title, [controls] the pane's.
 */
@Composable
internal fun ChatSubagentScreen(
  controller: ChatController,
  toolCallId: String,
  chatTitle: String,
  controls: PaneControls,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val process = controller.process
  val timeline by process.screenTimeline.collectAsStateWithLifecycle()
  val sessionFile by process.sessionFile.collectAsStateWithLifecycle()
  val subagents = rememberChatSubagents(process, timeline)
  val call = remember(timeline.items, toolCallId) {
    timeline.items.firstOrNull { it is TimelineItem.ToolCall && it.id == toolCallId } as? TimelineItem.ToolCall
  }
  val facts = subagentFacts(toolCallId, call, subagents, sessionFile)
  var attempt by remember { mutableIntStateOf(0) }
  val transcript by rememberSubagentTranscript(facts.source, facts.phase.active, facts.run?.messages ?: 0, attempt)
  val tabs = rememberWorkspaceTabs(controls.pane, process.cwd).count
  val run = facts.run
  SubagentScreen(
    chatTitle = chatTitle,
    facts = facts,
    transcript = transcript,
    onBack = onBack,
    onRetry = { attempt += 1 },
    modifier = modifier,
    trailing = {
      TabsButton(tabs, controls.onOpenTabs)
      CloseSplitButton(controls)
    },
    dialog = { PendingDialogCard(controller, timeline) },
  ) {
    if (run != null && facts.phase.active) {
      SteerBar(
        run = run,
        onSteer = { text -> controller.scope.launch { process.steerSubagent(run.id, text) } },
        onStop = { controller.scope.launch { process.stopSubagent(run.id) } },
      )
    } else {
      Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
    }
  }
}

/**
 * The box that steers a running subagent, docked like the chat's composer: the draft, Stop, and Send, which the
 * subagent reads after its current tool call. Send waits until the subagent leaves the queue, since a queued one has
 * nothing to read it yet.
 */
@Composable
private fun SteerBar(run: SubagentRun, onSteer: (String) -> Unit, onStop: () -> Unit) {
  val colors = pipkinColors
  val draft = remember(run.id) { TextFieldState() }
  val style = LocalTextStyle.current.copy(color = colors.foreground, fontSize = PipkinTokens.FontSize.content)
  val canSend = run.status == SubagentStatus.Running && draft.text.isNotBlank()
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(TRAY_SHAPE)
      .background(colors.surface1)
      .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
      .padding(start = 10.dp, end = 10.dp, bottom = PipkinTokens.Spacing.s4),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(modifier = Modifier.widthIn(max = PipkinTokens.contentMaxWidth).fillMaxWidth()) {
      BasicTextField(
        state = draft,
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = PipkinTokens.Spacing.s6, top = PipkinTokens.Spacing.s12, end = PipkinTokens.Spacing.s6)
          .atMostHalfTall()
          .heightIn(min = 30.dp),
        textStyle = style,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(colors.accent),
        decorator = TextFieldDecorator { inner ->
          Box(contentAlignment = Alignment.CenterStart) {
            if (draft.text.isEmpty()) {
              Text(
                text = stringResource(R.string.subagent_steer_placeholder, run.agent),
                style = style.copy(color = colors.foregroundMuted),
              )
            }
            inner()
          }
        },
      )
      Spacer(modifier = Modifier.height(PipkinTokens.Spacing.s8))
      Row(verticalAlignment = Alignment.CenterVertically) {
        StopButton(label = stringResource(R.string.subagent_stop), onClick = onStop)
        Spacer(modifier = Modifier.weight(1f))
        RoundIconButton(
          icon = R.drawable.ic_lucide_arrow_up,
          contentDescription = stringResource(R.string.subagent_steer_send, run.agent),
          onClick = {
            val text = draft.text.toString().trim()
            draft.clearText()
            onSteer(text)
          },
          enabled = canSend,
          background = colors.accent,
          tint = colors.accentForeground,
          iconSize = SEND_ICON_SIZE,
          modifier = Modifier.size(SEND_SIZE),
        )
      }
    }
  }
}
