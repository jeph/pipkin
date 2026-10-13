package io.jeph.pipkin.ui.workspace

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.ChatKey
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.terminal.TerminalSession
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SwipeToArchive
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.terminal.TerminalActionsSheet
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Paseo's compact-header tab switcher trigger: the number of chats in the workspace in a small outlined square. */
@Composable
internal fun TabCountButton(count: Int, onClick: () -> Unit) {
  val colors = pipkinColors
  val description = pluralStringResource(R.plurals.shell_tabs_button, count, count)
  Box(
    modifier = Modifier
      .size(28.dp)
      .clip(CircleShape)
      .clickable(onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
        .border(1.dp, colors.foregroundMuted, RoundedCornerShape(PipkinTokens.Radius.sm))
        .padding(horizontal = 2.dp),
      contentAlignment = Alignment.Center,
    ) {
      // Paseo's 12sp line, without Android's font padding, keeps the badge a 16dp square.
      Text(
        text = count.toString(),
        color = colors.foregroundMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        style = TextStyle(
          lineHeight = 12.sp,
          platformStyle = PlatformTextStyle(includeFontPadding = false),
          lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        ),
      )
    }
  }
}

/**
 * Paseo's mobile tab switcher as a sheet: the workspace's path over the tabs in the workspace of the shown chat
 * [process], its terminals and then its chats, the shown one highlighted. Tapping a tab shows it and closes the sheet.
 * A chat is archived by swiping it right or from its actions sheet, which also renames it; archiving closes the sheet,
 * so the shell's Undo shows. A terminal's actions sheet renames or closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceTabsSheet(process: AgentProcess, actions: ShellActions, onDismiss: () -> Unit) {
  val graph = LocalContext.current.appGraph
  val tabs = rememberWorkspaceTabs(process)
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val home = hello?.home?.takeIf { it.isNotEmpty() } ?: actions.home
  val latestDismiss by rememberUpdatedState(onDismiss)
  val archiving = remember(actions) { TabArchiving(actions, onArchived = { latestDismiss() }) }
  var menuFor by remember { mutableStateOf<SessionInfo?>(null) }
  var terminalMenuFor by remember { mutableStateOf<TerminalSession?>(null) }
  val show = { terminal: TerminalSession? ->
    graph.terminals.show(process, terminal)
    onDismiss()
  }
  LaunchedEffect(Unit) { graph.sessionRepository.refresh() }
  PipkinSheet(onDismissRequest = onDismiss, maxHeightFraction = 0.85f) {
    // A long path keeps its end, the folder that tells workspaces apart.
    Text(
      text = tildePath(process.cwd, home),
      modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.lg,
      maxLines = 1,
      overflow = TextOverflow.StartEllipsis,
    )
    TabList(
      process = process,
      tabs = tabs,
      archiving = archiving,
      callbacks = TabListCallbacks(
        // The chat shown next starts on its own tab, not on a terminal shown with this one.
        onOpenChat = { session -> if (actions.openSession(session)) show(null) },
        onShowChat = { show(null) },
        onShowTerminal = show,
        onChatActions = { menuFor = it },
        onTerminalActions = { terminalMenuFor = it },
      ),
      modifier = Modifier.weight(1f, fill = false),
    )
  }
  menuFor?.let { session ->
    SessionActions(
      session = session,
      onDismiss = { menuFor = null },
      onRename = { actions.rename(session, it) },
      onArchive = { archiving.archive(session) },
    )
  }
  terminalMenuFor?.let { terminal ->
    TerminalActionsSheet(
      session = terminal,
      onNewTerminal = null,
      onRestart = null,
      onClose = { graph.terminals.close(terminal) },
      onDismiss = { terminalMenuFor = null },
    )
  }
  ConfirmArchive(archiving)
}

/** "Stop and archive" for a slid-out chat Pi is working in. */
@Composable
private fun ConfirmArchive(archiving: TabArchiving) {
  if (archiving.confirming == null) return
  ConfirmDialog(
    title = stringResource(R.string.archive_stop_title),
    text = stringResource(R.string.archive_stop_text),
    confirmLabel = stringResource(R.string.archive_stop_confirm),
    onConfirm = archiving::confirm,
    onDismiss = archiving::cancel,
    destructive = true,
  )
}

/** What the tab menu's rows do. */
private class TabListCallbacks(
  val onOpenChat: (SessionInfo) -> Unit,
  val onShowChat: () -> Unit,
  val onShowTerminal: (TerminalSession) -> Unit,
  val onChatActions: (SessionInfo) -> Unit,
  val onTerminalActions: (TerminalSession) -> Unit,
)

/**
 * The tab menu's rows: the workspace's terminals, the shown chat while it has no saved history, then the saved chats.
 * The shown chat is highlighted only while no terminal is shown in its place.
 */
@Composable
private fun TabList(
  process: AgentProcess,
  tabs: WorkspaceTabs,
  archiving: TabArchiving,
  callbacks: TabListCallbacks,
  modifier: Modifier,
) {
  val processes by LocalContext.current.appGraph.registry.processes.collectAsStateWithLifecycle()
  val shownPath by process.sessionFile.collectAsStateWithLifecycle()
  val chatShown = tabs.shownTerminal == null
  LazyColumn(modifier = modifier) {
    items(tabs.terminals, key = { "terminal-${it.id}" }) { terminal ->
      TerminalTabRow(
        session = terminal,
        shown = terminal == tabs.shownTerminal,
        onOpen = { callbacks.onShowTerminal(terminal) },
        onActions = { callbacks.onTerminalActions(terminal) },
        modifier = Modifier.animateItem(),
      )
    }
    if (tabs.shownUnsaved) {
      item(key = UNSAVED_KEY) {
        val name by process.sessionName.collectAsStateWithLifecycle()
        ChatTabRow(
          title = name ?: stringResource(R.string.chat_new_chat),
          modified = null,
          process = process,
          shown = chatShown,
          onOpen = callbacks.onShowChat,
          onActions = null,
          modifier = Modifier.animateItem(),
        )
      }
    }
    items(tabs.chats, key = { it.path }) { session ->
      val current = session.path == shownPath
      SwipeToArchive(
        leaving = session.path in archiving.leaving,
        onSwiped = { archiving.archive(session) },
        onLeft = { archiving.onLeft(session) },
        containerColor = pipkinColors.surface1,
        modifier = Modifier.animateItem(),
      ) {
        ChatTabRow(
          title = sessionTitle(session),
          modified = session.modified,
          process = processes[ChatKey.session(session.path)],
          shown = current && chatShown,
          onOpen = { if (current) callbacks.onShowChat() else callbacks.onOpenChat(session) },
          onActions = { callbacks.onChatActions(session) },
        )
      }
    }
  }
}

/** The lazy-list key of the shown chat's row while it has no saved history. */
private const val UNSAVED_KEY = "unsaved"

/**
 * Archiving from the tab menu. A row first slides out; once it is gone the chat is archived and [onArchived] runs, or,
 * when Pi is working in it, the user is asked first and a cancelled row slides back.
 */
private class TabArchiving(private val actions: ShellActions, private val onArchived: () -> Unit) {
  /** Session files of rows sliding out, or out and waiting for the user. */
  var leaving by mutableStateOf(emptySet<String>())
    private set

  /** A slid-out chat Pi is working in, waiting for "Stop and archive". */
  var confirming by mutableStateOf<SessionInfo?>(null)
    private set

  fun archive(session: SessionInfo) {
    leaving += session.path
  }

  fun onLeft(session: SessionInfo) {
    if (actions.isWorking(session.path)) confirming = session else archiveNow(session)
  }

  fun confirm() {
    confirming?.let(::archiveNow)
    confirming = null
  }

  fun cancel() {
    confirming?.let { leaving -= it.path }
    confirming = null
  }

  private fun archiveNow(session: SessionInfo) {
    actions.archive(session)
    onArchived()
  }
}
