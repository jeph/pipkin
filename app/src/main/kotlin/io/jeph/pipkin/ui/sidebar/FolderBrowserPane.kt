package io.jeph.pipkin.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.RemoteHosts
import io.jeph.pipkin.host.FolderBrowser
import io.jeph.pipkin.host.FolderListing
import io.jeph.pipkin.host.FolderPlace
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.RemoteFailure
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.components.PillTone
import io.jeph.pipkin.ui.components.SearchField
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.onboarding.PrimaryButton
import io.jeph.pipkin.ui.remote.AutoReconnect
import io.jeph.pipkin.ui.remote.RemoteConnectDialogs
import io.jeph.pipkin.ui.remote.rememberRemoteConnector
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Browses folders the way Termux (and so Pi) sees them, on the phone or on a remote host chosen in the machine row,
 * through one `browse` host kept open while the pane is shown. [onChoose] gets the workspace folder for the current
 * folder: the folder itself on the phone, its stand-in for a remote one. A folder [isWorkspace] already accepts can't be
 * added again. The folder list takes whatever height the pane has, so short windows shrink the list, never the button.
 * Typing in the search field swaps the browser for search results across every place of the machine; tapping one
 * chooses it. [onOpenHost] opens a host's editor, or Add host for null.
 */
@Composable
internal fun FolderBrowserPane(
  isWorkspace: (cwd: String) -> Boolean,
  onOpenHost: (hostId: String?) -> Unit,
  modifier: Modifier = Modifier,
  onChoose: (String) -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val remotes by graph.remoteHosts.state.collectAsStateWithLifecycle()
  val connected by graph.remoteHosts.connected.collectAsStateWithLifecycle()
  val machine by graph.preferences.pickerMachine.collectAsStateWithLifecycle()
  // A host removed since it was last browsed leaves the phone.
  val host = machine?.let(remotes::host)
  val names = rememberFolderNames()
  val folders = names.picker(host)
  val connector = rememberRemoteConnector()
  // The folder shown on this machine, so rotating or Android recreating Pipkin comes back to it.
  var shown by rememberSaveable(host?.id) { mutableStateOf<String?>(null) }
  val state = rememberBrowserState(host?.id, start = shown ?: names.home.takeIf { host == null })
  LaunchedEffect(state) { snapshotFlow { state.listing?.path }.collect { path -> if (path != null) shown = path } }
  LaunchedEffect(Unit) { graph.remoteHosts.refresh() }
  LaunchedEffect(state) { state.open() }
  LaunchedEffect(state) { state.runSearches() }
  DisposableEffect(state) { onDispose(state::close) }
  val added = { path: String ->
    if (host == null) isWorkspace(path) else names.standIn(host.id, path)?.let(isWorkspace) == true
  }
  val choose = { path: String -> if (host == null) onChoose(path) else state.addRemote(path, onChoose) }
  Column(modifier = modifier) {
    SearchField(
      query = state.query,
      onQueryChange = { state.query = it },
      placeholder = stringResource(R.string.shell_browse_search),
      keyboardOptions = PATH_KEYBOARD,
    )
    MachineRow(
      host = host,
      hosts = remotes.hosts,
      connected = connected,
      onPick = graph.preferences::setPickerMachine,
      onAddHost = { onOpenHost(null) },
    )
    val query = state.query.trim()
    val unreachable = state.unreachable
    if (host != null) AutoReconnect(connector, host, unreachable?.code, onConnected = state::reopen)
    when {
      host != null && unreachable != null -> Unreachable(
        host = host,
        failure = unreachable,
        connecting = connector.connecting,
        onTryAgain = state::reopen,
        onEdit = { onOpenHost(host.id) },
        onConnect = { connector.connect(host.id, host.name, onConnected = state::reopen) },
      )

      query.isEmpty() -> Browser(state, folders, added, choose)

      else -> {
        // Searches need the browse host, so a host that failed to start answers every query with why.
        val found = state.startFailure?.let { FolderSearch(query, emptyList(), it) } ?: state.found
        FolderSearchResults(query, found, folders, added, Modifier.weight(1f), choose)
      }
    }
  }
  RemoteConnectDialogs(connector)
}

/** A browser session for remote host [hostId], or the phone for null, starting in [start]; a new one per machine. */
@Composable
private fun rememberBrowserState(hostId: String?, start: String?): FolderBrowserState {
  val graph = LocalContext.current.appGraph
  val scope = rememberCoroutineScope()
  return remember(hostId) { FolderBrowserState(graph.hostClient, graph.remoteHosts, scope, hostId, start) }
}

/** The place tabs, the shown folder's subfolders, and the button that adds that folder. */
@Composable
private fun ColumnScope.Browser(
  state: FolderBrowserState,
  folders: PickerFolders,
  isAdded: (path: String) -> Boolean,
  onChoose: (String) -> Unit,
) {
  val listing = state.listing
  val busy = state.loading || state.adding
  PlaceTabs(state.places, path = listing?.path, enabled = !busy, onPick = state::navigate)
  FolderList(state, Modifier.weight(1f))
  HorizontalDivider(thickness = 1.dp, color = pipkinColors.border)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 44.dp)
      .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s16, top = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    LucideIcon(icon = R.drawable.ic_lucide_folder_open, contentDescription = null, size = PipkinTokens.IconSize.lg)
    Text(
      text = (listing?.path ?: state.start)?.let(folders::path).orEmpty(),
      modifier = Modifier.weight(1f),
      style = pathTextStyle,
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.base,
      maxLines = 1,
      overflow = TextOverflow.MiddleEllipsis,
    )
    if (busy) SimmeringPot()
  }
  val added = listing != null && isAdded(listing.path)
  PrimaryButton(
    text = stringResource(if (added) R.string.shell_browse_added else R.string.shell_browse_add),
    onClick = { listing?.let { onChoose(it.path) } },
    modifier = Modifier.fillMaxWidth().padding(
      start = PipkinTokens.Spacing.s16,
      end = PipkinTokens.Spacing.s16,
      top = PipkinTokens.Spacing.s8,
      bottom = PipkinTokens.Spacing.s8,
    ),
    enabled = listing != null && listing.error == null && !busy && !added,
  )
}

/**
 * A remote host the browser couldn't reach, or that had no session free: why, as `ssh` put it, with Try again, Edit
 * host, and Connect when signing in or checking the host's key would fix it.
 */
@Composable
private fun ColumnScope.Unreachable(
  host: RemoteHost,
  failure: RemoteFailure,
  connecting: Boolean,
  onTryAgain: () -> Unit,
  onEdit: () -> Unit,
  onConnect: () -> Unit,
) {
  val colors = pipkinColors
  val title = when {
    failure.code == RemoteFailure.HOST_KEY_CHANGED -> stringResource(R.string.remote_key_changed_title, host.name)
    failure.code == RemoteFailure.SESSIONS_FULL -> stringResource(R.string.remote_sessions_full_title, host.name)
    failure.needsConnect -> stringResource(R.string.remote_connect_needed_title, host.name)
    else -> stringResource(R.string.remote_unreachable_title, host.name)
  }
  // A host that answered but has no session free is reachable; only one that didn't answer is offline.
  val answered = failure.needsConnect || failure.code == RemoteFailure.SESSIONS_FULL
  Column(
    modifier = Modifier
      .weight(1f)
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = PipkinTokens.Spacing.s24, vertical = PipkinTokens.Spacing.s24),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12, Alignment.CenterVertically),
  ) {
    Box(
      modifier = Modifier.size(UNREACHABLE_TILE).clip(CircleShape).background(colors.surface2),
      contentAlignment = Alignment.Center,
    ) {
      LucideIcon(
        icon = if (answered) R.drawable.ic_lucide_server else R.drawable.ic_lucide_wifi_off,
        contentDescription = null,
        tint = colors.foregroundMuted,
      )
    }
    Text(
      text = title,
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.lg,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
    )
    Text(
      text = failure.text.localized(),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      textAlign = TextAlign.Center,
    )
    if (connecting) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
      ) {
        SimmeringPot()
        Text(
          text = stringResource(R.string.remote_connecting, host.name),
          color = colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.sm,
        )
      }
      return@Column
    }
    UnreachableActions(connect = onConnect.takeIf { failure.needsConnect }, onTryAgain = onTryAgain, onEdit = onEdit)
  }
}

/** Connect when [connect] is given, over Try again and Edit host. */
@Composable
private fun UnreachableActions(connect: (() -> Unit)?, onTryAgain: () -> Unit, onEdit: () -> Unit) {
  if (connect != null) {
    PillButton(label = stringResource(R.string.remote_connect), onClick = connect, modifier = Modifier.fillMaxWidth())
  }
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    PillButton(
      label = stringResource(R.string.remote_try_again),
      onClick = onTryAgain,
      modifier = Modifier.weight(1f),
      tone = PillTone.Tonal,
    )
    PillButton(
      label = stringResource(R.string.remote_edit_host),
      onClick = onEdit,
      modifier = Modifier.weight(1f),
      tone = PillTone.Tonal,
    )
  }
}

/**
 * The machine's places as tabs: Termux home, phone storage and any SD cards on the phone; Home and Root on a remote
 * host. The tab holding the shown folder is underlined; none is when the folder is outside all of them.
 */
@Composable
private fun PlaceTabs(places: List<FolderPlace>, path: String?, enabled: Boolean, onPick: (String) -> Unit) {
  val colors = pipkinColors
  val selected = path?.let { placeIndexOf(places.map(FolderPlace::path), it) } ?: -1
  Column(modifier = Modifier.fillMaxWidth().padding(top = PipkinTokens.Spacing.s4)) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = PipkinTokens.Spacing.s8)) {
      places.forEachIndexed { index, place ->
        val label = when (place.kind) {
          "home" -> stringResource(R.string.shell_browse_home)
          "shared" -> stringResource(R.string.shell_browse_phone)
          "remote_home" -> stringResource(R.string.remote_place_home)
          "remote_root" -> stringResource(R.string.remote_place_root)
          else -> stringResource(R.string.shell_browse_volume, place.name ?: place.path)
        }
        val active = index == selected
        Column(
          modifier = Modifier
            .weight(1f)
            .selectable(selected = active, enabled = enabled, role = Role.Tab) { onPick(place.path) },
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text = label,
            modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s8, vertical = PipkinTokens.Spacing.s12),
            color = if (active) colors.foreground else colors.foregroundMuted,
            fontSize = PipkinTokens.FontSize.sm,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(2.dp)
              .background(if (active) colors.accent else Color.Transparent),
          )
        }
      }
    }
    HorizontalDivider(thickness = 1.dp, color = colors.border)
  }
}

@Composable
private fun FolderList(state: FolderBrowserState, modifier: Modifier) {
  val colors = pipkinColors
  val listing = state.listing
  LazyColumn(modifier = modifier.fillMaxWidth()) {
    val failure = state.failure
    val listingError = listing?.error
    if (failure != null) {
      item { Note(failure, colors.destructive) }
    } else if (listingError != null) {
      item { Note(listingError.localized(), colors.destructive) }
    }
    listing?.parent?.let { parent ->
      item {
        SheetRow(
          label = stringResource(R.string.shell_browse_up),
          icon = R.drawable.ic_lucide_folder_up,
          labelColor = colors.accent,
          enabled = !state.loading,
          onClick = { state.navigate(parent) },
        )
      }
    }
    if (listing != null && listing.error == null && listing.dirs.isEmpty()) {
      item { Note(stringResource(R.string.shell_browse_empty), colors.foregroundMuted) }
    }
    items(listing?.dirs.orEmpty(), key = { it }) { name ->
      SheetRow(
        label = name,
        icon = R.drawable.ic_lucide_folder,
        enabled = !state.loading,
        onClick = { state.navigate(if (listing?.path == "/") "/$name" else "${listing?.path}/$name") },
      )
    }
  }
}

@Composable
internal fun Note(text: String, color: Color) {
  Text(
    text = text,
    modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s12),
    color = color,
    fontSize = PipkinTokens.FontSize.sm,
  )
}

/**
 * One browser session for the pane, on the phone or on remote host [hostId]. All calls run on the main thread. [close]
 * may come while [open] is still starting the host; the host is then closed as soon as it arrives. [start] is the
 * folder shown first; a remote browser starts in the host's first place, its home.
 */
private class FolderBrowserState(
  private val hostClient: HostClient,
  private val remoteHosts: RemoteHosts,
  private val scope: CoroutineScope,
  private val hostId: String?,
  val start: String?,
) {
  var places by mutableStateOf(emptyList<FolderPlace>())
    private set
  var listing by mutableStateOf<FolderListing?>(null)
    private set
  var loading by mutableStateOf(true)
    private set
  var failure by mutableStateOf<String?>(null)
    private set

  /** Why the remote host can't be reached, which replaces the browser until the user tries again. */
  var unreachable by mutableStateOf<RemoteFailure?>(null)
    private set

  /** A remote folder's stand-in is being made. */
  var adding by mutableStateOf(false)
    private set
  var query by mutableStateOf("")

  /** The latest search's answer, which may still be for an earlier [query]. */
  var found by mutableStateOf<FolderSearch?>(null)
    private set

  private var browser by mutableStateOf<FolderBrowser?>(null)
  private var closed = false

  /**
   * The folder a new browse host lists first after the remote host was lost: the one whose listing lost it, else the
   * last one listed; null before any.
   */
  private var resumeAt: String? = null

  /** Why the browse host failed to start, once opening has given up. */
  val startFailure: String? get() = if (browser == null && !loading) failure else null

  suspend fun open() = load open@{
    val opened = hostClient.openFolderBrowser(hostId)
    if (closed) {
      opened.close()
      return@open null
    }
    val error = opened.error
    if (hostId != null && error != null) {
      // Whatever kept ssh from the host, the user can only try again or fix the host.
      opened.close()
      remoteHosts.noteFailure(hostId, error.code)
      unreachable = error
      return@open null
    }
    browser = opened
    places = opened.places
    if (hostId != null) remoteHosts.noteReached(hostId)
    opened.list(resumeAt ?: start ?: opened.places.firstOrNull()?.path ?: ROOT)
  }

  /** Starts over with a new browse host, after the remote host couldn't be reached. */
  fun reopen() {
    browser?.close()
    browser = null
    unreachable = null
    scope.launch { open() }
  }

  fun navigate(path: String) {
    val current = browser ?: return
    scope.launch { load { current.list(path) } }
  }

  /** Makes the stand-in of remote folder [path], then hands it to [onChoose]. */
  fun addRemote(path: String, onChoose: (String) -> Unit) {
    val id = hostId ?: return
    adding = true
    failure = null
    scope.launch {
      try {
        onChoose(remoteHosts.workspace(id, path))
      } catch (error: HostRejected) {
        fail(error)
      } catch (error: IOException) {
        failure = error.message.orEmpty()
      } finally {
        adding = false
      }
    }
  }

  /**
   * Searches for [query] once typing pauses, dropping a search the next keystroke outdates. A blank
   * query goes to the host too, which stops a search still running there.
   */
  suspend fun runSearches() {
    snapshotFlow { browser to query.trim() }.collectLatest { (current, text) ->
      if (current == null) return@collectLatest
      if (text.isNotEmpty()) delay(SEARCH_DEBOUNCE_MILLIS)
      found = try {
        FolderSearch(text, current.search(text), failure = null)
      } catch (error: HostRejected) {
        fail(error)
        FolderSearch(text, emptyList(), error.error)
      } catch (error: IOException) {
        FolderSearch(text, emptyList(), error.message.orEmpty())
      }
    }
  }

  fun close() {
    closed = true
    browser?.close()
  }

  /**
   * Shows the listing [block] returns; null leaves the shown one, as when the browser closed meanwhile. A remote
   * listing that failed for the connection shows the unreachable state instead, to come back to after reconnecting.
   */
  private suspend fun load(block: suspend () -> FolderListing?) {
    loading = true
    failure = null
    try {
      block()?.let(::show)
    } catch (error: HostRejected) {
      fail(error)
    } catch (error: IOException) {
      failure = error.message.orEmpty()
    } finally {
      loading = false
    }
  }

  private fun show(shown: FolderListing) {
    val id = hostId
    val code = shown.errorCode
    val error = shown.error
    if (id == null || code == null || error == null) {
      listing = shown
      if (error == null) resumeAt = shown.path
      return
    }
    remoteHosts.noteFailure(id, code)
    resumeAt = shown.path
    unreachable = RemoteFailure(code, error)
  }

  /** A remote refusal that means the connection is gone shows the unreachable state; any other is a note. */
  private fun fail(error: HostRejected) {
    val id = hostId
    if (id == null || !RemoteFailure.isDisconnect(error.code)) {
      failure = error.error
      return
    }
    remoteHosts.noteFailure(id, error.code)
    unreachable = RemoteFailure.of(error)
  }
}

/** Paseo waits this long after a keystroke before searching. */
private const val SEARCH_DEBOUNCE_MILLIS = 250L

private const val ROOT = "/"

private val UNREACHABLE_TILE = 56.dp

/** Paths: a `/` key on the keyboard, and no capitals or corrections. */
private val PATH_KEYBOARD = KeyboardOptions(
  capitalization = KeyboardCapitalization.None,
  autoCorrectEnabled = false,
  keyboardType = KeyboardType.Uri,
  imeAction = ImeAction.Search,
)
