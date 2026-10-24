package io.jeph.pipkin.ui.subagents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ChatTimeline
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.sessionTitle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException

/** The `subagent` tool call of an archived chat, once its transcript was read: null when it couldn't be found. */
private sealed interface ParentCall {
  data object Loading : ParentCall

  data class Read(val call: TimelineItem.ToolCall?) : ParentCall
}

/**
 * The subagent the archived chat in session file [path] started with its `subagent` tool call [toolCallId], read only
 * like its chat, with no Steer box. Leaves on its own once the chat is no longer archived.
 */
@Composable
internal fun ArchivedSubagentScreen(path: String, toolCallId: String, onBack: () -> Unit) {
  val graph = LocalContext.current.appGraph
  val archived by graph.sessionArchive.archived.collectAsStateWithLifecycle()
  val marks by graph.preferences.archivedSessions.collectAsStateWithLifecycle()
  LaunchedEffect(marks) { if (path !in marks) onBack() }
  val session = archived.firstOrNull { it.session.path == path }?.session
  val parent by rememberParentCall(path, toolCallId)
  val call = (parent as? ParentCall.Read)?.call
  val facts = subagentFacts(toolCallId, call, ChatSubagents.None, parentPath = path)
  var attempt by remember { mutableIntStateOf(0) }
  // Until the chat's own transcript says where the subagent's is, it isn't known.
  val source = facts.source.takeIf { parent is ParentCall.Read }
  val transcript by rememberSubagentTranscript(source, live = false, messages = 0, attempt = attempt)
  SubagentScreen(
    chatTitle = session?.let { sessionTitle(it) } ?: stringResource(R.string.archive_title),
    facts = facts,
    transcript = transcript,
    onBack = onBack,
    onRetry = { attempt += 1 },
  ) { ReadOnlyNote() }
}

/** The archived chat's `subagent` call [toolCallId], with the result that says where its transcript is. */
@Composable
private fun rememberParentCall(path: String, toolCallId: String): State<ParentCall> {
  val repository = LocalContext.current.appGraph.sessionRepository
  return produceState<ParentCall>(ParentCall.Loading, path, toolCallId) {
    value = try {
      val messages = repository.messages(path)
      val call = withContext(Dispatchers.Default) {
        val timeline = ChatTimeline()
        timeline.seed(messages)
        timeline.state.value.items.firstOrNull { it is TimelineItem.ToolCall && it.id == toolCallId }
      }
      ParentCall.Read(call as? TimelineItem.ToolCall)
    } catch (_: IOException) {
      // The chat's transcript can't be read now; the host can still find the subagent's among the chat's files.
      ParentCall.Read(null)
    } catch (_: HostRejected) {
      ParentCall.Read(null)
    } catch (_: JSONException) {
      ParentCall.Read(null)
    }
  }
}

/** In place of the Steer box: a card saying the subagent belongs to an archived chat and is read only. */
@Composable
private fun ReadOnlyNote() {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surface0)
      .navigationBarsPadding()
      .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s8)
      .clip(RoundedCornerShape(PipkinTokens.Radius.xxl))
      .background(colors.surface1)
      .padding(horizontal = PipkinTokens.Spacing.s12, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    Box(
      modifier = Modifier.size(32.dp).clip(CircleShape).background(colors.surface2),
      contentAlignment = Alignment.Center,
    ) {
      LucideIcon(icon = R.drawable.ic_lucide_archive, contentDescription = null, tint = colors.foregroundMuted)
    }
    Text(
      text = stringResource(R.string.subagent_archived_read_only),
      modifier = Modifier.weight(1f),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.meta,
    )
  }
}
