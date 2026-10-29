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
import io.jeph.pipkin.ui.chat.TurnStatus
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
import org.json.JSONArray
import org.json.JSONException

/** The least time between two reads of a running subagent's transcript, which grows with every message. */
private const val RELOAD_MILLIS = 1_000L

/** The host's code for a session file that isn't there. */
private const val NOT_FOUND = "not_found"

/** A subagent's transcript as its screen shows it. */
internal sealed interface SubagentTranscript {
  data object Loading : SubagentTranscript

  /**
   * The transcript's rows in [state]; [taskKey] keys its first user message, the task; [stats] counts it up. [history]
   * holds the messages read so far, which the next read adds to.
   */
  data class Loaded(
    val state: TimelineState,
    val taskKey: String?,
    val stats: TranscriptStats,
    val history: TranscriptHistory,
  ) : SubagentTranscript

  /**
   * The rows of a subagent the extension still lists, in [state], built from its events as they come; [taskKey] keys
   * the task, and [streamingArguments] gives a tool call's arguments while they stream.
   */
  class Live(val state: TimelineState, val taskKey: String?, val streamingArguments: (String) -> String?) :
    SubagentTranscript

  /** Its file is gone, as after the chat it belongs to was forked and the original deleted. */
  data object Gone : SubagentTranscript

  /** It ran and ended before it saved anything, or kept no file, as one of a chat not saved. */
  data object NotSaved : SubagentTranscript

  /** It hasn't started yet: it's queued. */
  data object Queued : SubagentTranscript

  /** It was stopped while queued, so it never ran. */
  data object NeverStarted : SubagentTranscript

  /** Its call was cut off, as when Android stopped Termux, before the subagent saved anything. */
  data object CutOff : SubagentTranscript

  /** It never began, for [reason]: Pi or an extension refused its call, or the call failed first. */
  data class StartFailed(val reason: String) : SubagentTranscript

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
    withoutFile(source)?.let {
      value = it
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
      value = SubagentTranscript.CutOff
      return@produceState
    }
    snapshotFlow { currentMessages }.conflate().collect {
      value = readTranscript(graph.sessionRepository, path, currentLive, value, failed, retry)
      delay(RELOAD_MILLIS)
    }
  }
}

/** What the screen shows for a [source] with no file to read, or null when it names or finds one. */
private fun withoutFile(source: TranscriptSource): SubagentTranscript? = when (source) {
  TranscriptSource.Starting -> SubagentTranscript.Loading
  TranscriptSource.Queued -> SubagentTranscript.Queued
  TranscriptSource.NeverStarted -> SubagentTranscript.NeverStarted
  TranscriptSource.None -> SubagentTranscript.NotSaved
  is TranscriptSource.StartFailed -> SubagentTranscript.StartFailed(source.reason)
  is TranscriptSource.File, is TranscriptSource.Lookup -> null
}

/** The transcript file [source] names, or finds among the chat's subagent files; null when it finds none. */
private suspend fun transcriptPath(hostClient: HostClient, source: TranscriptSource): String? = when (source) {
  is TranscriptSource.File -> source.path

  is TranscriptSource.Lookup ->
    hostClient.subagentSessions(source.parentPath).firstOrNull { it.toolCallId == source.toolCallId }?.path

  else -> null
}

/**
 * The transcript at [path] as rows. A read that fails keeps the [previous] rows of a transcript read before, since a
 * running subagent's file is read again and again; a file that isn't there is [SubagentTranscript.Gone], unless the
 * subagent is [live] and still starting. A failure with nothing to keep says [failed] when the error has no message,
 * and offers [retry]. Rows the [previous] read showed stay when the subagent compacts in between, as in the live chat,
 * with the compaction's notice after them.
 */
private suspend fun readTranscript(
  repository: SessionRepository,
  path: String,
  live: Boolean,
  previous: SubagentTranscript,
  failed: String,
  retry: () -> Unit,
): SubagentTranscript = try {
  val read = repository.messages(path)
  // Off the main thread: a long transcript takes a while to turn into rows.
  withContext(Dispatchers.Default) {
    val history = (previous as? SubagentTranscript.Loaded)?.history?.after(read) ?: TranscriptHistory.of(read)
    val timeline = ChatTimeline()
    timeline.seed(history.shown(), compactions = true)
    val state = timeline.state.value.withStream()
    SubagentTranscript.Loaded(
      state,
      state.items.firstOrNull {
        it is TimelineItem.User
      }?.key,
      transcriptStats(JSONArray(history.messages)),
      history,
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
  val live = transcript as? SubagentTranscript.Live
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
  val items = loaded?.state?.items ?: live?.state?.items
  val tool = toolKey?.let { key -> items?.firstOrNull { it.key == key } }
  // Only a live transcript has tool calls whose arguments still stream; one read from its file has none.
  tool?.let {
    ToolCallSheet(it, streamingArguments = live?.streamingArguments ?: { null }, onDismiss = { toolKey = null })
  }
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
      stats?.durationMillis(facts.cutOffAt)?.let { add(elapsedText(it)) }
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

    SubagentTranscript.Queued -> Note(stringResource(R.string.subagent_transcript_queued))

    SubagentTranscript.NeverStarted -> Note(stringResource(R.string.subagent_transcript_never_started))

    SubagentTranscript.CutOff -> Note(stringResource(R.string.subagent_transcript_cut_off))

    is SubagentTranscript.StartFailed -> Note(stringResource(R.string.subagent_not_started), transcript.reason)

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

    is SubagentTranscript.Loaded -> TranscriptRows(transcript.state, transcript.taskKey, view, onOpenTool)

    is SubagentTranscript.Live -> TranscriptRows(transcript.state, transcript.taskKey, view, onOpenTool)
  }
}

/** A transcript's rows in [state] as the chat shows its own, the task, keyed [taskKey], first. */
@Composable
private fun TranscriptRows(state: TimelineState, taskKey: String?, view: ChatView, onOpenTool: (String) -> Unit) {
  if (state.items.isEmpty()) {
    Waiting(R.string.subagent_transcript_starting)
    return
  }
  ChatStream(
    timeline = state,
    processState = AgentProcessState.Ready,
    view = view,
    onOpenTool = onOpenTool,
    onOpenProviders = {},
    onOpenOnboarding = {},
    onRestart = {},
    lastTurn = TurnStatus.None,
    // A subagent starts no subagents of its own.
    subagents = StreamSubagents(ChatSubagents.None, onOpen = {}, taskKey = taskKey),
  )
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
