package io.jeph.pipkin.ui.subagents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.LiveSubagents
import io.jeph.pipkin.agent.SubagentRun
import io.jeph.pipkin.agent.SubagentStatus
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.ui.chat.ChatController
import io.jeph.pipkin.ui.chat.PendingDialogCard
import io.jeph.pipkin.ui.chat.SteerButton
import io.jeph.pipkin.ui.chat.SteeringBox
import io.jeph.pipkin.ui.chat.SteeringSummary
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.atMostHalfTall
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.CloseSplitButton
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.TabsButton
import io.jeph.pipkin.ui.workspace.rememberWorkspaceTabs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val TRAY_SHAPE = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val SEND_SIZE = 40.dp
private val SEND_ICON_SIZE = 18.dp

/**
 * The subagent the chat's `subagent` tool call [toolCallId] started, in place of the chat in its pane: live while it
 * runs, read only once it's done. The chat's question card shows under the transcript, answered through the chat's
 * [controller], since the subagent's questions are the chat's. While it is active, a box steers it and Stop ends it,
 * with the steers it hasn't read yet over it, as the chat's composer has Pi's queue. [chatTitle] is the chat's title,
 * [controls] the pane's.
 */
@OptIn(ExperimentalLayoutApi::class)
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
  // Pipkin subagents standing aside after a reload or a reconnect, a call with no result is another extension's and
  // starts nothing to show; the screen closes, so it doesn't come back when the next connection starts.
  val gone = call != null && !subagents.startsSubagent(call)
  val latestOnBack by rememberUpdatedState(onBack)
  LaunchedEffect(gone) { if (gone) latestOnBack() }
  val facts = subagentFacts(toolCallId, call, subagents, sessionFile)
  val run = facts.run
  val live by rememberLiveTranscript(process.liveSubagents, run?.id)
  // While the extension lists the run, its events build the transcript; the file is read only once it isn't listed.
  val fileSource = facts.source.takeUnless {
    run != null && (it is TranscriptSource.File || it is TranscriptSource.Lookup)
  }
  val fromFile by rememberSubagentTranscript(fileSource, facts.phase.active, run?.messages ?: 0)
  val tabs = rememberWorkspaceTabs(controls.pane, process.cwd).count
  SubagentScreen(
    chatTitle = chatTitle,
    facts = facts,
    transcript = live ?: fromFile,
    onBack = onBack,
    modifier = modifier,
    trailing = {
      TabsButton(tabs, controls.onOpenTabs)
      CloseSplitButton(controls)
    },
    dialog = { PendingDialogCard(controller, timeline) },
  ) {
    if (run != null && facts.phase.active) {
      Column {
        // The keyboard and a dialog take the room the steers would use, as in the chat.
        val compact = WindowInsets.isImeVisible || timeline.pendingUi != null
        val draft = controller.view.steerDraft(run.toolCallId)
        // Weighted, so the steer box is measured first and keeps its height when the room runs short.
        PendingSubagentSteering(
          run = run,
          compact = compact,
          // As in the chat, not while Pi may be taking the steers for the subagent's next turn.
          canEdit = live?.state?.queueInFlight == false,
          onEdit = { controller.scope.launch { draft.putBack(process.liveSubagents.takeSteers(run.id)) } },
          modifier = Modifier.weight(1f, fill = false),
        )
        SteerBar(
          run = run,
          draft = draft,
          onSteer = { text -> controller.scope.launch { process.steerSubagent(run.id, text) } },
          onStop = { controller.scope.launch { process.stopSubagent(run.id) } },
        )
      }
    } else {
      Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
    }
  }
}

/**
 * Subagent [runId]'s transcript as it runs, followed over the chat's channel while the screen shows it; null while
 * the extension doesn't list the run, until it sent the log so far, and while the log holds no rows yet.
 */
@Composable
private fun rememberLiveTranscript(subagents: LiveSubagents, runId: String?): State<SubagentTranscript.Live?> =
  produceState<SubagentTranscript.Live?>(null, subagents, runId) {
    value = null
    if (runId == null) return@produceState
    try {
      subagents.follow(runId)
      subagents.of(runId).collectLatest { live ->
        value = null
        live?.screenStates?.collect { state ->
          value = state.takeIf { it.items.isNotEmpty() }?.let {
            val task = it.items.firstOrNull { item -> item is TimelineItem.User }
            SubagentTranscript.Live(it, task?.key, live.timeline::streamingArguments)
          }
        }
      }
    } finally {
      subagents.unfollow(runId)
    }
  }

/**
 * The steers [run] hasn't read yet, one per line, while there are any. Edit takes them back into the steer box when
 * [canEdit]; there is no Send now, since stopping a subagent's reply ends its run.
 */
@Composable
private fun PendingSubagentSteering(
  run: SubagentRun,
  compact: Boolean,
  canEdit: Boolean,
  onEdit: () -> Unit,
  modifier: Modifier,
) {
  if (run.steering.isEmpty()) return
  SteeringBox(
    summary = SteeringSummary(run.steering.joinToString("\n"), attachments = 0),
    compact = compact,
    modifier = modifier.padding(bottom = PipkinTokens.Spacing.s8),
  ) {
    SteerButton(stringResource(R.string.chat_steering_edit), canEdit, filled = false, onClick = onEdit)
  }
}

/** Puts [steers] back in the steer box, one per line, ahead of anything typed since, as the chat's Edit does. */
private fun TextFieldState.putBack(steers: List<String>) {
  val taken = steers.filter { it.isNotBlank() }.joinToString("\n")
  if (taken.isEmpty()) return
  val typed = text
  setTextAndPlaceCursorAtEnd(if (typed.isBlank()) taken else "$taken\n$typed")
}

/**
 * The box that steers a running subagent, docked like the chat's composer: the [draft], kept with the chat's view as
 * the composer's is, so it outlives rotation, Stop, and Send, which the subagent reads after its current tool call.
 */
@Composable
private fun SteerBar(run: SubagentRun, draft: TextFieldState, onSteer: (String) -> Unit, onStop: () -> Unit) {
  val colors = pipkinColors
  val style = LocalTextStyle.current.copy(color = colors.foreground, fontSize = PipkinTokens.FontSize.content)
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
        SteerSendButton(run, draft, onSteer)
      }
    }
  }
}

/**
 * Sends the [draft] to [run]. It waits until the subagent leaves the queue, since a queued one has nothing to read it
 * yet, and hides the keyboard as the chat's Send does, so the steers show in full over the box.
 */
@Composable
private fun SteerSendButton(run: SubagentRun, draft: TextFieldState, onSteer: (String) -> Unit) {
  val colors = pipkinColors
  val keyboard = LocalSoftwareKeyboardController.current
  val focus = LocalFocusManager.current
  RoundIconButton(
    icon = R.drawable.ic_lucide_arrow_up,
    contentDescription = stringResource(R.string.subagent_steer_send, run.agent),
    onClick = {
      val text = draft.text.toString().trim()
      draft.clearText()
      onSteer(text)
      keyboard?.hide()
      focus.clearFocus()
    },
    enabled = run.status == SubagentStatus.Running && draft.text.isNotBlank(),
    background = colors.accent,
    tint = colors.accentForeground,
    iconSize = SEND_ICON_SIZE,
    modifier = Modifier.size(SEND_SIZE),
  )
}
