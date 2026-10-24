package io.jeph.pipkin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.PaneHeader

/**
 * Session name (or the first message) over the project path, or over the statuses extensions set while there are any,
 * in the pane's header.
 */
@Composable
internal fun ChatHeader(process: AgentProcess, controls: PaneControls, modifier: Modifier) {
  val statuses by process.extensionStatuses.collectAsStateWithLifecycle()
  val colors = pipkinColors
  val status = remember(statuses, colors) { extensionStatusLine(statuses, colors) }
  PaneHeader(
    title = chatTitle(process),
    cwd = process.cwd,
    controls = controls,
    modifier = modifier,
    status = status,
  )
}

/**
 * The chat's title, as its header and its subagents' breadcrumbs show it: the one an extension set, else its session's
 * name, else its first message.
 */
@Composable
internal fun chatTitle(process: AgentProcess): String {
  val graph = LocalContext.current.appGraph
  val sessionName by process.sessionName.collectAsStateWithLifecycle()
  val extensionTitle by process.extensionTitle.collectAsStateWithLifecycle()
  val sessionFile by process.sessionFile.collectAsStateWithLifecycle()
  val sessions by graph.sessionRepository.sessions.collectAsStateWithLifecycle()
  val firstMessage = sessions.firstOrNull { it.path == sessionFile }?.firstMessage?.takeIf { it.isNotBlank() }
  return extensionTitle ?: sessionName ?: firstMessage ?: stringResource(R.string.chat_new_chat)
}
