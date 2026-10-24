package io.jeph.pipkin.ui.archive

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.ArchivedSession
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.HeaderButton
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SearchField
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.SnackbarStack
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.relativeTime
import io.jeph.pipkin.ui.workspace.sessionTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Archived chats, most recently archived first, searchable by title or workspace folder. Tapping a chat opens its
 * read-only transcript; a long press starts selecting for bulk Restore and Delete. A chat whose folder is gone can
 * be deleted but not restored, alone or in bulk.
 */
@Composable
internal fun ArchiveScreen(actions: ShellActions, onBack: () -> Unit, onOpenChat: (path: String) -> Unit) {
  val graph = LocalContext.current.appGraph
  val archived by graph.sessionArchive.archived.collectAsStateWithLifecycle()
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val home = hello?.home?.takeIf { it.isNotEmpty() } ?: actions.home
  val page = rememberArchivePage(actions)
  var query by rememberSaveable { mutableStateOf("") }
  // Folders may have come or gone since the list was read, which decides what can be restored.
  LaunchedEffect(Unit) { graph.sessionRepository.refresh() }
  LaunchedEffect(archived) { page.keepSelected(archived.map { it.session.path }.toSet()) }
  BackHandler(enabled = page.selected.isNotEmpty()) { page.selected = emptySet() }
  val shown = remember(archived, query, home) { archived.filter { it.session.matches(query.trim(), home) } }
  val selectedSessions = archived.map { it.session }.filter { it.path in page.selected }
  val restorable = selectedSessions.filter { it.cwdExists }
  Box(modifier = Modifier.fillMaxSize().background(pipkinColors.surface0)) {
    Column(modifier = Modifier.fillMaxSize()) {
      ArchiveHeader(
        selectedCount = page.selected.size,
        onBack = onBack,
        onClearSelection = { page.selected = emptySet() },
        onSelectAll = { page.selected += shown.map { it.session.path } },
      )
      SearchField(
        query = query,
        onQueryChange = { query = it },
        placeholder = stringResource(R.string.archive_search),
        modifier = Modifier.padding(vertical = PipkinTokens.Spacing.s8),
      )
      ArchiveList(
        page = page,
        shown = shown,
        archiveEmpty = archived.isEmpty(),
        home = home,
        onOpenChat = onOpenChat,
        modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
      )
      if (page.selected.isNotEmpty()) {
        BulkActions(
          restoreCount = restorable.size,
          deleteCount = selectedSessions.size,
          enabled = !page.busy,
          onRestore = { page.restore(restorable) },
          onDelete = { page.deleting = selectedSessions },
        )
      }
    }
    SnackbarStack(
      state = page.snackbar,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(PipkinTokens.Spacing.s16)
        .padding(bottom = if (page.selected.isNotEmpty()) 72.dp else 0.dp),
    )
  }
  ArchivePageOverlays(page)
}

/**
 * The archive page's selection, open sheet and delete confirmation, and its restore and delete. Outcomes show in
 * [snackbar]; a delete runs on [appScope] so leaving the page does not cut it short.
 */
private class ArchivePage(
  private val actions: ShellActions,
  private val appScope: CoroutineScope,
  private val screenScope: CoroutineScope,
  private val resources: Resources,
  private val genericError: String,
  selected: Set<String>,
) {
  val snackbar = SnackbarStackState()
  var selected by mutableStateOf(selected)
  var sheetFor by mutableStateOf<ArchivedSession?>(null)
  var deleting by mutableStateOf<List<SessionInfo>?>(null)

  /** True while a delete runs. */
  var busy by mutableStateOf(false)
    private set

  fun toggle(path: String) {
    selected = if (path in selected) selected - path else selected + path
  }

  /** Drops selected chats that left the archive. */
  fun keepSelected(archived: Set<String>) {
    if (selected.any { it !in archived }) selected = selected intersect archived
  }

  fun restore(sessions: List<SessionInfo>) {
    val restored = actions.restore(sessions)
    if (restored.isEmpty()) return
    selected -= restored.map { it.path }.toSet()
    show(resources.getQuantityString(R.plurals.archive_restored, restored.size, restored.size))
  }

  fun delete(sessions: List<SessionInfo>) {
    val paths = sessions.map { it.path }
    busy = true
    val result = appScope.async { actions.deleteArchived(paths) }
    screenScope.launch {
      val failure = result.await()
      busy = false
      if (failure != null) {
        show(failure.ifBlank { genericError })
        return@launch
      }
      selected -= paths.toSet()
      show(resources.getQuantityString(R.plurals.archive_deleted, paths.size, paths.size))
    }
  }

  private fun show(message: String) {
    screenScope.launch { snackbar.show(message) }
  }
}

/** An [ArchivePage] whose selection survives configuration changes. */
@Composable
private fun rememberArchivePage(actions: ShellActions): ArchivePage {
  val appScope = LocalContext.current.appGraph.scope
  val screenScope = rememberCoroutineScope()
  val resources = LocalResources.current
  val genericError = stringResource(R.string.shell_error_generic)
  val create = { selected: Set<String> ->
    ArchivePage(actions, appScope, screenScope, resources, genericError, selected)
  }
  return rememberSaveable(saver = listSaver(save = { it.selected.toList() }, restore = { create(it.toSet()) })) {
    create(emptySet())
  }
}

/** The archived chats [shown] by the search, or why there are none. */
@Composable
private fun ArchiveList(
  page: ArchivePage,
  shown: List<ArchivedSession>,
  archiveEmpty: Boolean,
  home: String,
  onOpenChat: (path: String) -> Unit,
  modifier: Modifier,
) {
  val loaded by LocalContext.current.appGraph.sessionRepository.loaded.collectAsStateWithLifecycle()
  Box(modifier = modifier) {
    when {
      archiveEmpty && !loaded -> SimmeringPot(modifier = Modifier.align(Alignment.Center))

      shown.isEmpty() -> Text(
        text = stringResource(if (archiveEmpty) R.string.archive_empty else R.string.archive_no_matches),
        modifier = Modifier.align(Alignment.Center),
        color = pipkinColors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.base,
      )

      else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(shown, key = { it.session.path }) { entry ->
          val path = entry.session.path
          ArchivedRow(
            entry = entry,
            home = home,
            selecting = page.selected.isNotEmpty(),
            selected = path in page.selected,
            onClick = { if (page.selected.isEmpty()) onOpenChat(path) else page.toggle(path) },
            onLongClick = { page.selected += path },
            onActions = { page.sheetFor = entry },
            modifier = Modifier.animateItem(),
          )
        }
      }
    }
  }
}

@Composable
private fun ArchivePageOverlays(page: ArchivePage) {
  page.sheetFor?.let { entry ->
    ArchivedChatActions(
      session = entry.session,
      enabled = !page.busy,
      onDismiss = { page.sheetFor = null },
      onRestore = { page.restore(listOf(entry.session)) },
      onDelete = { page.deleting = listOf(entry.session) },
    )
  }
  page.deleting?.let { sessions ->
    DeleteDialog(
      count = sessions.size,
      onConfirm = {
        page.deleting = null
        page.delete(sessions)
      },
      onDismiss = { page.deleting = null },
      subagents = sessions.sumOf { it.subagents },
    )
  }
}

/** Whether [query] (already trimmed) is in the chat's title or its folder, as a full or `~` path. */
private fun SessionInfo.matches(query: String, home: String): Boolean = query.isEmpty() ||
  listOfNotNull(name, firstMessage, cwd, tildePath(cwd, home)).any { it.contains(query, ignoreCase = true) }

@Composable
private fun ArchiveHeader(
  selectedCount: Int,
  onBack: () -> Unit,
  onClearSelection: () -> Unit,
  onSelectAll: () -> Unit,
) {
  if (selectedCount == 0) {
    HeaderBar(
      title = stringResource(R.string.archive_title),
      leading = {
        HeaderButton(
          icon = R.drawable.ic_lucide_chevron_left,
          contentDescription = stringResource(R.string.archive_back),
          onClick = onBack,
        )
      },
    )
    return
  }
  HeaderBar(
    title = pluralStringResource(R.plurals.archive_selected, selectedCount, selectedCount),
    leading = {
      HeaderButton(
        icon = R.drawable.ic_lucide_x,
        contentDescription = stringResource(R.string.archive_clear_selection),
        onClick = onClearSelection,
      )
    },
    trailing = {
      TextButton(onClick = onSelectAll) {
        Text(stringResource(R.string.archive_select_all), color = pipkinColors.accent)
      }
    },
  )
}

/**
 * One archived chat: its title over its folder (flagged when the folder is gone) and how many subagents it ran, and
 * when it was archived. While selecting, a check circle leads and tapping toggles the selection.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchivedRow(
  entry: ArchivedSession,
  home: String,
  selecting: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onActions: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = pipkinColors
  val session = entry.session
  val title = sessionTitle(session)
  val clickLabel = when {
    !selecting -> null
    selected -> stringResource(R.string.archive_deselect, title)
    else -> stringResource(R.string.archive_select, title)
  }
  val shape = RoundedCornerShape(ROW_RADIUS)
  val highlight = if (selected) {
    Modifier.background(colors.accentSoft).border(1.5.dp, colors.accent.copy(alpha = SELECTED_RING_ALPHA), shape)
  } else {
    Modifier
  }
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = PipkinTokens.Spacing.s8, vertical = PipkinTokens.Spacing.s2)
      .clip(shape)
      .then(highlight)
      .combinedClickable(onClickLabel = clickLabel, onClick = onClick, onLongClick = onLongClick)
      .heightIn(min = 56.dp)
      .padding(
        start = PipkinTokens.Spacing.s12,
        end = PipkinTokens.Spacing.s4,
        top = PipkinTokens.Spacing.s8,
        bottom = PipkinTokens.Spacing.s8,
      ),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    if (selecting) SelectionCircle(selected)
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      FolderAndSubagents(session, home)
    }
    Text(
      text = relativeTime(entry.archivedAt),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      maxLines = 1,
    )
    if (!selecting) {
      RoundIconButton(
        icon = R.drawable.ic_lucide_ellipsis,
        contentDescription = stringResource(R.string.archive_chat_actions),
        onClick = onActions,
        tint = colors.foregroundMuted,
      )
    }
  }
}

private val ROW_RADIUS = 16.dp
private const val SELECTED_RING_ALPHA = 0.5f

/** The chat's [FolderLine], then how many subagents it ran, whose transcripts stay with it. */
@Composable
private fun FolderAndSubagents(session: SessionInfo, home: String) {
  val colors = pipkinColors
  Row(verticalAlignment = Alignment.CenterVertically) {
    FolderLine(session, home, Modifier.weight(1f, fill = false))
    val count = session.subagents
    if (count == 0) return@Row
    Text(
      text = stringResource(R.string.separator_dot),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
    )
    LucideIcon(
      icon = R.drawable.ic_lucide_bot,
      contentDescription = null,
      modifier = Modifier.padding(end = PipkinTokens.Spacing.s4),
      size = PipkinTokens.IconSize.xs,
      tint = colors.foregroundMuted,
    )
    Text(
      text = pluralStringResource(R.plurals.archive_subagents, count, count),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      maxLines = 1,
      softWrap = false,
    )
  }
}

/** The chat's folder with `~` for home, led by "Folder missing" once the folder is gone. */
@Composable
internal fun FolderLine(session: SessionInfo, home: String, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    if (!session.cwdExists) {
      Text(
        text = stringResource(R.string.archive_folder_missing),
        color = colors.destructive,
        fontSize = PipkinTokens.FontSize.sm,
        maxLines = 1,
      )
    }
    Text(
      text = tildePath(session.cwd, home),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** One archived chat's sheet: Restore (unless its folder is gone) and Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchivedChatActions(
  session: SessionInfo,
  enabled: Boolean,
  onDismiss: () -> Unit,
  onRestore: () -> Unit,
  onDelete: () -> Unit,
) {
  PipkinSheet(onDismissRequest = onDismiss) {
    SheetRow(
      label = stringResource(R.string.archive_restore),
      icon = R.drawable.ic_lucide_archive_restore,
      value = if (session.cwdExists) null else stringResource(R.string.archive_folder_missing),
      enabled = enabled && session.cwdExists,
      onClick = {
        onDismiss()
        onRestore()
      },
    )
    SheetRow(
      label = stringResource(R.string.archive_delete),
      icon = R.drawable.ic_lucide_trash_2,
      labelColor = pipkinColors.destructive,
      enabled = enabled,
      onClick = {
        onDismiss()
        onDelete()
      },
    )
  }
}

/**
 * Asks before deleting [count] chats for good; [subagents], how many subagents they ran together, says their
 * transcripts go too.
 */
@Composable
internal fun DeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit, subagents: Int = 0) {
  val text = when {
    subagents == 0 -> pluralStringResource(R.plurals.archive_delete_text, count)
    count == 1 -> pluralStringResource(R.plurals.archive_delete_text_subagents, subagents, subagents)
    else -> pluralStringResource(R.plurals.archive_delete_text_many_subagents, subagents, subagents)
  }
  ConfirmDialog(
    title = pluralStringResource(R.plurals.archive_delete_title, count, count),
    text = text,
    confirmLabel = stringResource(R.string.archive_delete),
    onConfirm = onConfirm,
    onDismiss = onDismiss,
    destructive = true,
    icon = R.drawable.ic_lucide_trash_2,
  )
}
