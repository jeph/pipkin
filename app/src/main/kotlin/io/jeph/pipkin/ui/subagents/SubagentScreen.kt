package io.jeph.pipkin.ui.subagents

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.ChatTimeline
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.agent.TimelineState
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.SessionRepository
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.ui.chat.ChatStack
import io.jeph.pipkin.ui.chat.ChatStream
import io.jeph.pipkin.ui.chat.ChatView
import io.jeph.pipkin.ui.chat.StreamSubagents
import io.jeph.pipkin.ui.chat.ToolCallSheet
import io.jeph.pipkin.ui.chat.elapsedText
import io.jeph.pipkin.ui.components.HeaderBarHeight
import io.jeph.pipkin.ui.components.HeaderButton
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.json.JSONException

/** The least time between two reads of a running subagent's transcript, which grows with every message. */
private const val RELOAD_MILLIS = 1_000L

/** The host's code for a session file that isn't there. */
private const val NOT_FOUND = "not_found"

/** A subagent's transcript as its screen shows it. */
internal sealed interface SubagentTranscript {
  data object Loading : SubagentTranscript

  /** The transcript's rows in [state]; [taskKey] keys its first user message, the task; [stats] counts it up. */
  data class Loaded(val state: TimelineState, val taskKey: String?, val stats: TranscriptStats) : SubagentTranscript

  /** Its file is gone, as after the chat it belongs to was forked and the original deleted. */
  data object Gone : SubagentTranscript

  /** The subagent kept no file, as one of a chat not saved. */
  data object NotSaved : SubagentTranscript

  /** It never began, for [reason]: Pi or an extension refused its call, or the call failed first. */
  data class NotStarted(val reason: String) : SubagentTranscript

  /** It couldn't be read, for [message]; [retry] reads it again. */
  class Failed(val message: String, val retry: () -> Unit) : SubagentTranscript
}

/**
 * The transcript at [source], read again whenever the run's [messages] count changes, at most once a [RELOAD_MILLIS],
 * and when a failed read is retried. A null [source] isn't known yet, so the transcript stays loading. While the
 * subagent is [live], a file that isn't there yet means it is starting: Pi writes a session's file with its first reply.
 */
@Composable
internal fun rememberSubagentTranscript(
  source: TranscriptSource?,
  live: Boolean,
  messages: Int,
): State<SubagentTranscript> {
  val graph = LocalContext.current.appGraph
  val failed = stringResource(R.string.subagent_transcript_failed)
  val currentLive by rememberUpdatedState(live)
  val currentMessages by rememberUpdatedState(messages)
  val attempt = remember { mutableIntStateOf(0) }
  val retry = remember(attempt) { { attempt.intValue += 1 } }
  return produceState<SubagentTranscript>(SubagentTranscript.Loading, source, attempt.intValue) {
    value = SubagentTranscript.Loading
    if (source == null) return@produceState
    if (source is TranscriptSource.NotStarted) {
      value = SubagentTranscript.NotStarted(source.reason)
      return@produceState
    }
    val path = try {
      transcriptPath(graph.hostClient, source)
    } catch (error: HostRejected) {
      value = SubagentTranscript.Failed(error.error, retry)
      return@produceState
    } catch (error: IOException) {
      value = SubagentTranscript.Failed(error.message ?: failed, retry)
      return@produceState
    }
    if (path == null) {
      value = if (source is TranscriptSource.None) SubagentTranscript.NotSaved else SubagentTranscript.Gone
      return@produceState
    }
    snapshotFlow { currentMessages }.conflate().collect {
      value = readTranscript(graph.sessionRepository, path, currentLive, value, failed, retry)
      delay(RELOAD_MILLIS)
    }
  }
}

/** The transcript file [source] names, or finds among the chat's subagent files; null when there is none. */
private suspend fun transcriptPath(hostClient: HostClient, source: TranscriptSource): String? = when (source) {
  is TranscriptSource.File -> source.path

  is TranscriptSource.Lookup ->
    hostClient.subagentSessions(source.parentPath).firstOrNull { it.toolCallId == source.toolCallId }?.path

  is TranscriptSource.NotStarted, TranscriptSource.None -> null
}

/**
 * The transcript at [path] as rows. A read that fails keeps the [previous] rows of a transcript read before, since a
 * running subagent's file is read again and again; a file that isn't there is [SubagentTranscript.Gone], unless the
 * subagent is [live] and still starting. A failure with nothing to keep says [failed] when the error has no message,
 * and offers [retry].
 */
private suspend fun readTranscript(
  repository: SessionRepository,
  path: String,
  live: Boolean,
  previous: SubagentTranscript,
  failed: String,
  retry: () -> Unit,
): SubagentTranscript = try {
  val messages = repository.messages(path)
  // Off the main thread: a long transcript takes a while to turn into rows.
  withContext(Dispatchers.Default) {
    val timeline = ChatTimeline()
    timeline.seed(messages)
    val state = timeline.state.value.withStream()
    SubagentTranscript.Loaded(
      state,
      state.items.firstOrNull {
        it is TimelineItem.User
      }?.key,
      transcriptStats(messages),
    )
  }
} catch (error: HostRejected) {
  when {
    error.code == NOT_FOUND && live -> previous
    error.code == NOT_FOUND -> SubagentTranscript.Gone
    else -> previous as? SubagentTranscript.Loaded ?: SubagentTranscript.Failed(error.error, retry)
  }
} catch (error: IOException) {
  previous as? SubagentTranscript.Loaded ?: SubagentTranscript.Failed(error.message ?: failed, retry)
} catch (_: JSONException) {
  previous as? SubagentTranscript.Loaded ?: SubagentTranscript.Failed(failed, retry)
}

/**
 * A subagent in place of its chat: a header with the way back, the chat's [chatTitle] and the subagent's agent and
 * task, its status, turn, tokens and time, then its transcript with the chat's own rows, the task first. [trailing]
 * ends the header; [dialog] and [bottom] go under the transcript, as the chat's question card and composer do.
 */
@Composable
internal fun SubagentScreen(
  chatTitle: String,
  facts: SubagentFacts,
  transcript: SubagentTranscript,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  trailing: @Composable RowScope.() -> Unit = {},
  dialog: @Composable () -> Unit = {},
  bottom: @Composable () -> Unit,
) {
  // Kept while the transcript is read again, so a running subagent's rows keep their place and parsed replies.
  val view = remember(facts.toolCallId) { ChatView() }
  var toolKey by remember(facts.toolCallId) { mutableStateOf<String?>(null) }
  val loaded = transcript as? SubagentTranscript.Loaded
  Column(modifier = modifier.fillMaxSize().background(pipkinColors.surface0)) {
    SubagentHeader(chatTitle, facts, loaded?.stats, onBack, trailing)
    ChatStack(
      modifier = Modifier.weight(1f),
      stream = { TranscriptBody(transcript, facts.phase.active, view, onOpenTool = { toolKey = it }) },
      dialog = dialog,
      steering = {},
      composer = bottom,
    )
  }
  val tool = toolKey?.let { key -> loaded?.state?.items?.firstOrNull { it.key == key } }
  // A transcript is read from its file, so no tool call has arguments still streaming.
  tool?.let { ToolCallSheet(it, streamingArguments = { null }, onDismiss = { toolKey = null }) }
}

/** Back, the chat's title as a breadcrumb over the subagent's agent and task, and its status line under them. */
@Composable
private fun SubagentHeader(
  chatTitle: String,
  facts: SubagentFacts,
  stats: TranscriptStats?,
  onBack: () -> Unit,
  trailing: @Composable RowScope.() -> Unit,
) {
  val colors = pipkinColors
  val breadcrumb = stringResource(R.string.subagent_breadcrumb, chatTitle)
  Column(modifier = Modifier.fillMaxWidth().background(colors.surface0).windowInsetsPadding(WindowInsets.statusBars)) {
    Row(
      modifier = Modifier
        .heightIn(min = HeaderBarHeight)
        .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s4),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      HeaderButton(
        icon = R.drawable.ic_lucide_chevron_left,
        contentDescription = stringResource(R.string.subagent_back),
        onClick = onBack,
      )
      Column(modifier = Modifier.weight(1f).padding(horizontal = PipkinTokens.Spacing.s4)) {
        Row(
          modifier = Modifier.clearAndSetSemantics { contentDescription = breadcrumb },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s2),
        ) {
          Text(
            text = chatTitle,
            modifier = Modifier.weight(1f, fill = false),
            color = colors.foregroundMuted,
            fontSize = PipkinTokens.FontSize.sm,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          LucideIcon(
            icon = R.drawable.ic_lucide_chevron_right,
            contentDescription = null,
            size = PipkinTokens.IconSize.xs,
            tint = colors.foregroundMuted,
          )
        }
        Text(
          text = stringResource(R.string.subagent_title, facts.agent, facts.description),
          color = colors.foreground,
          fontSize = PipkinTokens.FontSize.title,
          fontWeight = FontWeight.ExtraBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      trailing()
    }
    StatusLine(facts, stats)
  }
}

/**
 * The subagent's status, then its turn, tokens and time: the run's own while the extension lists it, else counted
 * from its transcript, with the tokens its result reported.
 */
@Composable
private fun StatusLine(facts: SubagentFacts, stats: TranscriptStats?) {
  val colors = pipkinColors
  val numbers = remember { NumberFormat.getIntegerInstance() }
  val separator = stringResource(R.string.separator_dot)
  val run = facts.run
  val parts = buildList {
    if (run != null) {
      if (run.turn > 0) add(stringResource(R.string.subagent_turn, numbers.format(run.turn)))
      if (run.tokens > 0) add(tokensText(run.tokens))
      runTime(run)?.let(::add)
    } else {
      stats?.turns?.takeIf {
        it > 0
      }?.let { add(pluralStringResource(R.plurals.subagent_turns, it, numbers.format(it))) }
      (facts.resultTokens ?: stats?.tokens)?.takeIf { it > 0 }?.let { add(tokensText(it)) }
      stats?.durationMillis?.let { add(elapsedText(it)) }
    }
  }
  val phaseColor = phaseColor(facts.phase)
  val text = buildAnnotatedString {
    withStyle(SpanStyle(color = phaseColor, fontWeight = FontWeight.SemiBold)) {
      append(stringResource(facts.phase.label))
    }
    for (part in parts) append(separator + part)
  }
  Text(
    text = text,
    modifier = Modifier.padding(
      start = PipkinTokens.Spacing.s16,
      end = PipkinTokens.Spacing.s16,
      bottom = PipkinTokens.Spacing.s8,
    ),
    color = colors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.sm,
    style = LocalTextStyle.current.copy(fontFeatureSettings = PipkinFonts.TABULAR),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/** The transcript in the chat's own rows, or what stands in for it while it's read, gone or failed. */
@Composable
private fun TranscriptBody(
  transcript: SubagentTranscript,
  live: Boolean,
  view: ChatView,
  onOpenTool: (String) -> Unit,
) {
  when (transcript) {
    SubagentTranscript.Loading -> Waiting(if (live) R.string.subagent_transcript_starting else null)

    SubagentTranscript.Gone -> Note(stringResource(R.string.subagent_transcript_gone))

    SubagentTranscript.NotSaved -> Note(stringResource(R.string.subagent_transcript_none))

    is SubagentTranscript.NotStarted -> Note(stringResource(R.string.subagent_not_started), transcript.reason)

    is SubagentTranscript.Failed -> Column(
      modifier = Modifier.fillMaxSize().padding(PipkinTokens.Spacing.s24),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = transcript.message,
        color = pipkinColors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.base,
        textAlign = TextAlign.Center,
      )
      PillButton(label = stringResource(R.string.subagent_retry), onClick = transcript.retry)
    }

    is SubagentTranscript.Loaded -> {
      if (transcript.state.items.isEmpty()) {
        Waiting(R.string.subagent_transcript_starting)
        return
      }
      ChatStream(
        timeline = transcript.state,
        processState = AgentProcessState.Ready,
        view = view,
        onOpenTool = onOpenTool,
        onOpenProviders = {},
        onOpenOnboarding = {},
        onRestart = {},
        backgroundTasks = 0,
        // A subagent starts no subagents of its own.
        subagents = StreamSubagents(ChatSubagents.None, onOpen = {}, taskKey = transcript.taskKey),
      )
    }
  }
}

/** A simmering pot, over [text] when there is one. */
@Composable
private fun Waiting(@StringRes text: Int?) {
  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    SimmeringPot()
    text?.let {
      Text(text = stringResource(it), color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.base)
    }
  }
}

/** [text], centred, over [detail] when there is one. */
@Composable
private fun Note(text: String, detail: String? = null) {
  Column(
    modifier = Modifier.fillMaxSize().padding(PipkinTokens.Spacing.s24),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = text,
      color = pipkinColors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
      textAlign = TextAlign.Center,
    )
    detail?.let {
      Text(
        text = it,
        color = pipkinColors.foreground,
        fontSize = PipkinTokens.FontSize.sm,
        textAlign = TextAlign.Center,
      )
    }
  }
}
