package io.jeph.pipkin.ui.workspace

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import io.jeph.pipkin.files.FileTab
import io.jeph.pipkin.files.FileTabRegistry
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.terminal.TerminalRegistry
import io.jeph.pipkin.terminal.TerminalSession
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SnackbarStackOverlay
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.components.SwipeAway
import io.jeph.pipkin.ui.files.FileActionsSheet
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.terminal.TerminalActionsSheet
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.workspace.WorkspaceTab

/** Paseo's compact-header tab switcher trigger: the number of tabs in the workspace in a small outlined square. */
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
 * [process], its terminals, its open files and then its chats, the shown one highlighted. Tapping a tab shows it and
 * closes the sheet. A chat is archived by swiping it right or from its actions sheet, which also renames it; the sheet
 * stays open and shows the shell's [snackbar], so the archive's Undo is in reach. A terminal or a file closes by
 * swiping it right or from its actions sheet, which also renames a terminal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceTabsSheet(
  process: AgentProcess,
  actions: ShellActions,
  snackbar: SnackbarStackState,
  onDismiss: () -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val tabs = rememberWorkspaceTabs(process)
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val home = hello?.home?.takeIf { it.isNotEmpty() } ?: actions.home
  val archiving = remember(actions) { TabArchiving(actions) }
  val terminalClosing = remember(graph) { TerminalClosing(graph.terminals) }
  var menu by remember { mutableStateOf<TabMenu?>(null) }
  val show = { tab: WorkspaceTab? ->
    graph.shownTab.show(process, tab)
    onDismiss()
  }
  LaunchedEffect(Unit) { graph.sessionRepository.refresh() }
  LaunchedEffect(tabs.chats) { archiving.forgetGone(tabs.chats) }
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
      terminalClosing = terminalClosing,
      callbacks = TabListCallbacks(
        // The chat shown next starts on its own tab, not on a terminal or file shown with this one.
        onOpenChat = { session -> if (actions.openSession(session)) show(null) },
        onShow = show,
        onActions = { menu = it },
      ),
      modifier = Modifier.weight(1f, fill = false),
    )
    SnackbarStackOverlay(snackbar)
  }
  menu?.let { TabMenuSheet(it, actions, archiving, onDismiss = { menu = null }) }
  ConfirmArchive(archiving)
  ConfirmCloseTerminal(terminalClosing)
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

/** "Close terminal?" for a slid-out terminal that may have something running. */
@Composable
private fun ConfirmCloseTerminal(closing: TerminalClosing) {
  if (closing.confirming == null) return
  ConfirmDialog(
    title = stringResource(R.string.terminal_close_confirm_title),
    text = stringResource(R.string.terminal_close_confirm_text),
    confirmLabel = stringResource(R.string.terminal_close_confirm),
    onConfirm = closing::confirm,
    onDismiss = closing::cancel,
    destructive = true,
  )
}

/** A tab whose actions sheet is open. */
private sealed interface TabMenu {
  data class Chat(val session: SessionInfo) : TabMenu

  data class Terminal(val session: TerminalSession) : TabMenu

  data class File(val file: FileTab) : TabMenu
}

/** [menu]'s actions sheet: a chat's renames or archives it, a terminal's renames or closes it, a file's closes it. */
@Composable
private fun TabMenuSheet(menu: TabMenu, actions: ShellActions, archiving: TabArchiving, onDismiss: () -> Unit) {
  val graph = LocalContext.current.appGraph
  when (menu) {
    is TabMenu.Chat -> SessionActions(
      session = menu.session,
      onDismiss = onDismiss,
      onRename = { actions.rename(menu.session, it) },
      onArchive = { archiving.archive(menu.session) },
    )

    is TabMenu.Terminal -> TerminalActionsSheet(
      session = menu.session,
      onNewTerminal = null,
      onRestart = null,
      onClose = { graph.terminals.close(menu.session) },
      onDismiss = onDismiss,
    )

    is TabMenu.File -> FileActionsSheet(file = menu.file, onClose = {
      graph.files.close(menu.file)
    }, onDismiss = onDismiss)
  }
}

/** What the tab menu's rows do. */
private class TabListCallbacks(
  val onOpenChat: (SessionInfo) -> Unit,
  val onShow: (WorkspaceTab?) -> Unit,
  val onActions: (TabMenu) -> Unit,
)

/**
 * The tab menu's rows: the workspace's terminals and open files, the shown chat while it has no saved history, then
 * the saved chats. The shown chat is highlighted only while no other tab is shown in its place.
 */
@Composable
private fun TabList(
  process: AgentProcess,
  tabs: WorkspaceTabs,
  archiving: TabArchiving,
  terminalClosing: TerminalClosing,
  callbacks: TabListCallbacks,
  modifier: Modifier,
) {
  val graph = LocalContext.current.appGraph
  val processes by graph.registry.processes.collectAsStateWithLifecycle()
  val shownPath by process.sessionFile.collectAsStateWithLifecycle()
  val closing = remember(graph) { FileClosing(graph.files) }
  val chatShown = tabs.shownTab == null
  LazyColumn(modifier = modifier) {
    terminalRows(tabs, terminalClosing, callbacks)
    fileRows(tabs, closing, callbacks)
    if (tabs.shownUnsaved) {
      item(key = UNSAVED_KEY) {
        val name by process.sessionName.collectAsStateWithLifecycle()
        ChatTabRow(
          title = name ?: stringResource(R.string.chat_new_chat),
          modified = null,
          process = process,
          shown = chatShown,
          onOpen = { callbacks.onShow(null) },
          onActions = null,
          modifier = Modifier.animateItem(),
        )
      }
    }
    items(tabs.chats, key = { it.path }) { session ->
      val current = session.path == shownPath
      SwipeAway(
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
          onOpen = { if (current) callbacks.onShow(null) else callbacks.onOpenChat(session) },
          onActions = { callbacks.onActions(TabMenu.Chat(session)) },
        )
      }
    }
  }
}

/** The workspace's terminals; one closes by swiping it right, as a file does. */
private fun LazyListScope.terminalRows(tabs: WorkspaceTabs, closing: TerminalClosing, callbacks: TabListCallbacks) {
  items(tabs.terminals, key = { "terminal-${it.id}" }) { terminal ->
    val tab = WorkspaceTab.Terminal(terminal)
    SwipeAway(
      leaving = terminal.id in closing.leaving,
      onSwiped = { closing.close(terminal) },
      onLeft = { closing.onLeft(terminal) },
      containerColor = pipkinColors.surface1,
      modifier = Modifier.animateItem(),
      mark = R.drawable.ic_lucide_x,
    ) {
      TerminalTabRow(
        session = terminal,
        shown = tab == tabs.shownTab,
        onOpen = { callbacks.onShow(tab) },
        onActions = { callbacks.onActions(TabMenu.Terminal(terminal)) },
      )
    }
  }
}

/** The workspace's open files; one closes by swiping it right, as a chat archives. */
private fun LazyListScope.fileRows(tabs: WorkspaceTabs, closing: FileClosing, callbacks: TabListCallbacks) {
  items(tabs.files, key = { "file-${it.id}" }) { file ->
    val tab = WorkspaceTab.File(file)
    SwipeAway(
      leaving = file.id in closing.leaving,
      onSwiped = { closing.close(file) },
      onLeft = { closing.onLeft(file) },
      containerColor = pipkinColors.surface1,
      modifier = Modifier.animateItem(),
      mark = R.drawable.ic_lucide_x,
    ) {
      FileTabRow(
        file = file,
        shown = tab == tabs.shownTab,
        onOpen = { callbacks.onShow(tab) },
        onActions = { callbacks.onActions(TabMenu.File(file)) },
      )
    }
  }
}

/** The lazy-list key of the shown chat's row while it has no saved history. */
private const val UNSAVED_KEY = "unsaved"

/** Closing files from the tab menu: a row first slides out, then its file closes. */
private class FileClosing(private val files: FileTabRegistry) {
  /** Ids of files whose rows are sliding out. */
  var leaving by mutableStateOf(emptySet<Int>())
    private set

  fun close(file: FileTab) {
    leaving += file.id
  }

  fun onLeft(file: FileTab) {
    files.close(file)
    leaving -= file.id
  }
}

/**
 * Closing terminals from the tab menu. A row first slides out; once it is gone the terminal closes, or, when it may
 * have something running, the user is asked first and a cancelled row slides back.
 */
private class TerminalClosing(private val terminals: TerminalRegistry) {
  /** Ids of terminals whose rows are sliding out, or out and waiting for the user. */
  var leaving by mutableStateOf(emptySet<Int>())
    private set

  /** A slid-out terminal that may have something running, waiting for "Close". */
  var confirming by mutableStateOf<TerminalSession?>(null)
    private set

  fun close(session: TerminalSession) {
    leaving += session.id
  }

  fun onLeft(session: TerminalSession) {
    if (session.mayHaveWorkRunning()) confirming = session else remove(session)
  }

  fun confirm() {
    confirming?.let(::remove)
    confirming = null
  }

  fun cancel() {
    confirming?.let { leaving -= it.id }
    confirming = null
  }

  private fun remove(session: TerminalSession) {
    terminals.close(session)
    leaving -= session.id
  }
}

/**
 * Archiving from the tab menu. A row first slides out; once it is gone the chat is archived, or, when Pi is working in
 * it, the user is asked first and a cancelled row slides back.
 */
private class TabArchiving(private val actions: ShellActions) {
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
    if (actions.isWorking(session.path)) confirming = session else actions.archive(session)
  }

  fun confirm() {
    confirming?.let(actions::archive)
    confirming = null
  }

  fun cancel() {
    confirming?.let { leaving -= it.path }
    confirming = null
  }

  /** Forgets rows that left [chats], so a chat Undo brings back shows instead of staying slid out. */
  fun forgetGone(chats: List<SessionInfo>) {
    val listed = chats.mapTo(HashSet()) { it.path }
    leaving = leaving.filterTo(HashSet()) { it in listed }
  }
}
