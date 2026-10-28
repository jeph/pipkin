package io.jeph.pipkin.ui.workspace

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalWindowInfo
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.ChatKey
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SnackbarStackOverlay
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.components.SwipeAway
import io.jeph.pipkin.ui.components.scrollFades
import io.jeph.pipkin.ui.sidebar.pathTextStyle
import io.jeph.pipkin.ui.sidebar.projectName
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.WorkspaceTab

/**
 * The session sheet: the workspace's name and path over the tabs in the workspace folder [cwd] of what [pane] shows,
 * its terminals, its open files, its open commits and then its chats, the shown one highlighted, and under them what
 * the shown tab can do, from [shownTab]. Tapping a tab shows it in [pane] instead and closes the sheet; tapping one the
 * other pane shows calls [onSwapPanes], so that pane comes to this side. A chat is archived by swiping it right or from
 * its actions sheet, which also renames it and, when [onShownInSplit] is given, opens it in split screen, calling that
 * once the second pane shows it; the sheet stays open and shows the shell's [snackbar], so the archive's Undo is in
 * reach. A terminal, a file or a commit closes by swiping it right or from its actions sheet, which also renames a
 * terminal.
 */
@Composable
internal fun WorkspaceTabsSheet(
  pane: Pane,
  cwd: String,
  actions: ShellActions,
  snackbar: SnackbarStackState,
  shownTab: ShownTabActions,
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
  LaunchedEffect(Unit) { graph.sessionRepository.refresh() }
  LaunchedEffect(tabs.chats) { archiving.forgetGone(tabs.chats) }
  var closedAs by remember { mutableStateOf<ClosedAs?>(null) }
  // A short window, such as a phone on its side, has no room for the tiles under the tabs without squeezing the tabs
  // out, so there the tiles scroll at the end of the list instead.
  val pinActions = LocalWindowInfo.current.containerDpSize.height >= PINNED_ACTIONS_MIN_HEIGHT
  PipkinSheet(onDismissRequest = onDismiss, maxHeightFraction = 0.85f) { dismiss ->
    // A chat opens before the sheet closes, since a chat that can't open keeps it open with a notice. The sheet then
    // keeps showing what it showed, so the opened chat doesn't reorder the rows or swap the tiles as it slides away.
    val closeOnOpened = {
      closedAs = ClosedAs(tabs, shownTab.current)
      dismiss { closedAs = null }
    }
    val swapPanes = { dismiss(onSwapPanes) }
    val show = { tab: WorkspaceTab -> dismiss { if (!graph.tabs.show(pane, tab)) onSwapPanes() } }
    val closed = closedAs
    val actionTiles = if (closed != null) closed.actions else shownTab.current
    TabsSheetTitle(name = projectName(cwd, home), path = tildePath(cwd, home))
    TabsLabels()
    TabList(
      tabs = closed?.tabs ?: tabs,
      archiving = archiving,
      closing = closing,
      callbacks = TabListCallbacks(
        onOpenChat = { session -> if (actions.openChat(pane, session.cwd, session.path)) closeOnOpened() },
        onShow = show,
        onSwapPanes = swapPanes,
        onActions = { menu = it },
      ),
      footer = actionTiles?.takeUnless { pinActions }?.let { { TabActionGrid(it, dismiss) } },
      modifier = Modifier.weight(1f, fill = false),
    )
    if (pinActions) actionTiles?.let { TabActionGrid(it, dismiss) }
    SnackbarStackOverlay(snackbar)
    // Within the sheet's content, so opening a chat in split screen from the actions sheet slides this one away too.
    menu?.let { shownMenu ->
      // Only chats no pane shows open in split screen: the second pane's own rows already show there.
      val path = (shownMenu as? TabMenu.Chat)?.session?.path
      val openInSplit = onShownInSplit?.let { shown ->
        { session: SessionInfo ->
          if (actions.openChat(Pane.Secondary, session.cwd, session.path)) {
            shown()
            closeOnOpened()
          }
        }
      }?.takeIf { path != tabs.shown.chat && path != tabs.shown.elsewhereChat }
      TabMenuSheet(shownMenu, actions, archiving, openInSplit, onDismiss = { menu = null })
    }
  }
  ConfirmArchive(archiving)
  ConfirmCloseTab(closing)
}

/**
 * The workspace's name in bold over its folder; a long path keeps its end, the folder that tells workspaces apart. The
 * home folder's name is its path, `~`, so it has no second line.
 */
@Composable
private fun TabsSheetTitle(name: String, path: String) {
  val colors = pipkinColors
  Column(
    modifier = Modifier.padding(
      start = 20.dp,
      end = 20.dp,
      top = PipkinTokens.Spacing.s4,
      bottom = PipkinTokens.Spacing.s12,
    ),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    Text(
      text = name,
      color = colors.foreground,
      fontSize = 22.sp,
      fontWeight = FontWeight.ExtraBold,
      letterSpacing = (-0.025).em,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (path == name) return@Column
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
    ) {
      LucideIcon(
        icon = R.drawable.ic_lucide_folder,
        contentDescription = null,
        size = PipkinTokens.IconSize.sm,
        tint = colors.foregroundMuted,
      )
      Text(
        text = path,
        style = pathTextStyle,
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.meta,
        maxLines = 1,
        overflow = TextOverflow.StartEllipsis,
      )
    }
  }
}

/** The labels over the tabs: what they are, and that a swipe closes one. */
@Composable
private fun TabsLabels() {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp)
      .padding(bottom = PipkinTokens.Spacing.s6),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SectionLabel(stringResource(R.string.shell_tabs_label), Modifier.weight(1f))
    SectionLabel(stringResource(R.string.shell_tabs_swipe_hint))
  }
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

/** The [tabs] and the shown tab's [actions] the session sheet showed when opening a chat closed it. */
private class ClosedAs(val tabs: WorkspaceTabs, val actions: List<TabAction>?)

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
 * The tab menu's rows: what the pane shows first, then the workspace's other open tabs, then its other saved chats,
 * then [footer] when given. What the pane shows is highlighted; opening the chat the other pane shows swaps the panes.
 * An edge with more rows past it fades into the sheet, so a list cut short reads as one that scrolls.
 */
@Composable
private fun TabList(
  tabs: WorkspaceTabs,
  archiving: TabArchiving,
  closing: TabClosing,
  callbacks: TabListCallbacks,
  footer: (@Composable () -> Unit)?,
  modifier: Modifier,
) {
  val graph = LocalContext.current.appGraph
  val processes by graph.registry.processes.collectAsStateWithLifecycle()
  val keptTitles by graph.sessionRepository.extensionTitles.bySessionFile.collectAsStateWithLifecycle()
  val keptStatuses by graph.sessionRepository.extensionStatuses.bySessionFile.collectAsStateWithLifecycle()
  val listState = rememberLazyListState()
  // The list keeps its first visible row in place, so a shown tab that changes while the sheet is open, as when the
  // shown terminal closes, would land just above the view; going back to the top keeps it in sight.
  val shownKey = tabs.shownChat?.path ?: tabs.shown.tab?.key
  LaunchedEffect(shownKey) { listState.requestScrollToItem(0) }
  val fadeTo = pipkinColors.surface1
  val chatRow: @Composable LazyItemScope.(SessionInfo) -> Unit = { session ->
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
  LazyColumn(modifier = modifier.scrollFades(listState, fadeTo), state = listState) {
    tabs.shownChat?.let { shown -> item(key = shown.path) { chatRow(shown) } }
    tabRows(tabs, closing, callbacks)
    items(tabs.otherChats, key = { it.path }) { chatRow(it) }
    footer?.let { item(key = FOOTER_KEY) { it() } }
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

/** The window height under which the shown tab's tiles scroll at the end of the tabs rather than stay under them. */
private val PINNED_ACTIONS_MIN_HEIGHT = 600.dp

/** The list key of the tiles when they scroll at the end of the tabs. */
private const val FOOTER_KEY = "pipkin:tab-actions"
