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
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SnackbarStackOverlay
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.components.SwipeAway
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.WorkspaceTab

/**
 * Paseo's mobile tab switcher as a sheet: the workspace's path over the tabs in the workspace folder [cwd] of what
 * [pane] shows, its terminals, its open files, its open commits and then its chats, the shown one highlighted. Tapping
 * a tab shows it in [pane] instead and closes the sheet; tapping one the other pane shows calls [onSwapPanes], so
 * that pane comes to this side. A chat is archived by swiping it right or from its actions sheet, which also renames it
 * and, when [onShownInSplit] is given, opens it in split screen, calling that once the second pane shows it; the sheet
 * stays open and shows the shell's [snackbar], so the archive's Undo is in reach. A terminal, a file or a commit
 * closes by swiping it right or from its actions sheet, which also renames a terminal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceTabsSheet(
  pane: Pane,
  cwd: String,
  actions: ShellActions,
  snackbar: SnackbarStackState,
  onShownInSplit: (() -> Unit)?,
  onSwapPanes: () -> Unit,
  onDismiss: () -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val tabs = rememberWorkspaceTabs(pane, cwd)
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val home = hello?.home?.takeIf { it.isNotEmpty() } ?: actions.home
  val archiving = remember(actions) { TabArchiving(actions) }
  val closing = remember(actions) { TabClosing(actions) }
  var menu by remember { mutableStateOf<TabMenu?>(null) }
  val swapPanes = {
    onSwapPanes()
    onDismiss()
  }
  val show = { tab: WorkspaceTab -> if (graph.tabs.show(pane, tab)) onDismiss() else swapPanes() }
  LaunchedEffect(Unit) { graph.sessionRepository.refresh() }
  LaunchedEffect(tabs.chats) { archiving.forgetGone(tabs.chats) }
  PipkinSheet(onDismissRequest = onDismiss, maxHeightFraction = 0.85f) {
    // A long path keeps its end, the folder that tells workspaces apart.
    Text(
      text = tildePath(cwd, home),
      modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.lg,
      maxLines = 1,
      overflow = TextOverflow.StartEllipsis,
    )
    TabList(
      tabs = tabs,
      archiving = archiving,
      closing = closing,
      callbacks = TabListCallbacks(
        onOpenChat = { session -> if (actions.openChat(pane, session.cwd, session.path)) onDismiss() },
        onShow = show,
        onSwapPanes = swapPanes,
        onActions = { menu = it },
      ),
      modifier = Modifier.weight(1f, fill = false),
    )
    SnackbarStackOverlay(snackbar)
  }
  menu?.let { shownMenu ->
    // Only chats no pane shows open in split screen: the second pane's own rows already show there.
    val path = (shownMenu as? TabMenu.Chat)?.session?.path
    val openInSplit = onShownInSplit?.let { shown ->
      { session: SessionInfo ->
        if (actions.openChat(Pane.Secondary, session.cwd, session.path)) {
          shown()
          onDismiss()
        }
      }
    }?.takeIf { path != tabs.shown.chat && path != tabs.shown.elsewhereChat }
    TabMenuSheet(shownMenu, actions, archiving, openInSplit, onDismiss = { menu = null })
  }
  ConfirmArchive(archiving)
  ConfirmCloseTab(closing)
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
    icon = R.drawable.ic_lucide_archive,
  )
}

/** The [CloseWarning] of a slid-out tab, such as a terminal that may have something running. */
@Composable
private fun ConfirmCloseTab(closing: TabClosing) {
  val warning = closing.confirming?.warning ?: return
  ConfirmDialog(
    title = stringResource(warning.title),
    text = stringResource(warning.text),
    confirmLabel = stringResource(warning.confirm),
    onConfirm = closing::confirm,
    onDismiss = closing::cancel,
    destructive = true,
    icon = warning.icon,
  )
}

/** A tab whose actions sheet is open. */
private sealed interface TabMenu {
  data class Chat(val session: SessionInfo) : TabMenu

  data class Tab(val tab: WorkspaceTab) : TabMenu
}

/**
 * [menu]'s actions sheet: a chat's renames or archives it, and opens it in split screen through [openInSplit] when
 * given; a tab's are its kind's, with its close.
 */
@Composable
private fun TabMenuSheet(
  menu: TabMenu,
  actions: ShellActions,
  archiving: TabArchiving,
  openInSplit: ((SessionInfo) -> Unit)?,
  onDismiss: () -> Unit,
) {
  when (menu) {
    is TabMenu.Chat -> SessionActions(
      session = menu.session,
      onDismiss = onDismiss,
      onRename = { actions.rename(menu.session, it) },
      onArchive = { archiving.archive(menu.session) },
      onOpenInSplit = openInSplit?.let { open -> { open(menu.session) } },
    )

    is TabMenu.Tab -> WorkspaceTabActionsSheet(menu.tab, onClose = {
      actions.closeTab(menu.tab)
    }, onDismiss = onDismiss)
  }
}

/** What the tab menu's rows do. */
private class TabListCallbacks(
  val onOpenChat: (SessionInfo) -> Unit,
  val onShow: (WorkspaceTab) -> Unit,
  val onSwapPanes: () -> Unit,
  val onActions: (TabMenu) -> Unit,
)

/**
 * The tab menu's rows: the workspace's open tabs, then the saved chats. What the pane shows is highlighted; opening the
 * chat the other pane shows swaps the panes.
 */
@Composable
private fun TabList(
  tabs: WorkspaceTabs,
  archiving: TabArchiving,
  closing: TabClosing,
  callbacks: TabListCallbacks,
  modifier: Modifier,
) {
  val graph = LocalContext.current.appGraph
  val processes by graph.registry.processes.collectAsStateWithLifecycle()
  val keptTitles by graph.sessionRepository.extensionTitles.bySessionFile.collectAsStateWithLifecycle()
  val keptStatuses by graph.sessionRepository.extensionStatuses.bySessionFile.collectAsStateWithLifecycle()
  LazyColumn(modifier = modifier) {
    tabRows(tabs, closing, callbacks)
    items(tabs.chats, key = { it.path }) { session ->
      val elsewhere = session.path == tabs.shown.elsewhereChat
      SwipeAway(
        leaving = session.path in archiving.leaving,
        onSwiped = { archiving.archive(session) },
        onLeft = { archiving.onLeft(session) },
        containerColor = pipkinColors.surface1,
        modifier = Modifier.animateItem(),
      ) {
        val process = processes[ChatKey.session(session.path)]
        ChatTabRow(
          title = chatTitle(session, process, keptTitles[session.path]),
          modified = session.modified,
          statuses = chatStatuses(process, keptStatuses[session.path]),
          process = process,
          current = session.path == tabs.shown.chat,
          onOpen = { if (elsewhere) callbacks.onSwapPanes() else callbacks.onOpenChat(session) },
          onActions = { callbacks.onActions(TabMenu.Chat(session)) },
        )
      }
    }
  }
}

/** The workspace's open tabs; one closes by swiping it right, as a chat archives. */
private fun LazyListScope.tabRows(tabs: WorkspaceTabs, closing: TabClosing, callbacks: TabListCallbacks) {
  items(tabs.tabs, key = { it.key }) { tab ->
    SwipeAway(
      leaving = tab.key in closing.leaving,
      onSwiped = { closing.close(tab) },
      onLeft = { closing.onLeft(tab) },
      containerColor = pipkinColors.surface1,
      modifier = Modifier.animateItem(),
      mark = R.drawable.ic_lucide_x,
    ) {
      WorkspaceTabRow(
        tab = tab,
        current = tab == tabs.shown.tab,
        onOpen = { callbacks.onShow(tab) },
        onActions = { callbacks.onActions(TabMenu.Tab(tab)) },
      )
    }
  }
}

/**
 * Closing tabs from the tab menu. A row first slides out; once it is gone the tab closes, or, when its kind gives a
 * [CloseWarning], the user is asked first and a cancelled row slides back.
 */
private class TabClosing(private val actions: ShellActions) {
  /** A slid-out tab and the warning the user is asked to confirm, kept while asking even if the tab calms down. */
  class Confirming(val tab: WorkspaceTab, val warning: CloseWarning)

  /** Keys of tabs whose rows are sliding out, or out and waiting for the user. */
  var leaving by mutableStateOf(emptySet<String>())
    private set

  var confirming by mutableStateOf<Confirming?>(null)
    private set

  fun close(tab: WorkspaceTab) {
    leaving += tab.key
  }

  fun onLeft(tab: WorkspaceTab) {
    val warning = tab.closeWarning()
    if (warning != null) confirming = Confirming(tab, warning) else remove(tab)
  }

  fun confirm() {
    confirming?.let { remove(it.tab) }
    confirming = null
  }

  fun cancel() {
    confirming?.let { leaving -= it.tab.key }
    confirming = null
  }

  private fun remove(tab: WorkspaceTab) {
    actions.closeTab(tab)
    leaving -= tab.key
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
