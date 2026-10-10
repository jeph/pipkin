package io.jeph.pipkin.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.FolderBrowser
import io.jeph.pipkin.host.FolderListing
import io.jeph.pipkin.host.FolderPlace
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.components.Spinner
import io.jeph.pipkin.ui.onboarding.PrimaryButton
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Browses folders the way Termux (and so Pi) sees them, through one `browse` host kept open while
 * the pane is shown. [onChoose] gets the current folder, unless [isWorkspace] says it already is
 * one. The folder list takes whatever height the pane has, so short windows shrink the list, never
 * the button.
 */
@Composable
internal fun FolderBrowserPane(
  home: String,
  isWorkspace: (cwd: String) -> Boolean,
  modifier: Modifier = Modifier,
  onChoose: (String) -> Unit,
) {
  val hostClient = LocalContext.current.appGraph.hostClient
  val scope = rememberCoroutineScope()
  val state = remember { FolderBrowserState(hostClient, scope, home) }
  LaunchedEffect(state) { state.open() }
  DisposableEffect(state) { onDispose(state::close) }
  val listing = state.listing
  Column(modifier = modifier) {
    PlaceTabs(state.places, path = listing?.path, enabled = !state.loading, onPick = state::navigate)
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
        text = tildePath(listing?.path ?: home, home),
        modifier = Modifier.weight(1f),
        color = pipkinColors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
      )
      if (state.loading) Spinner()
    }
    val added = listing != null && isWorkspace(listing.path)
    PrimaryButton(
      text = stringResource(if (added) R.string.shell_browse_added else R.string.shell_browse_add),
      onClick = { listing?.let { onChoose(it.path) } },
      modifier = Modifier.fillMaxWidth().padding(
        start = PipkinTokens.Spacing.s16,
        end = PipkinTokens.Spacing.s16,
        top = PipkinTokens.Spacing.s8,
        bottom = PipkinTokens.Spacing.s8,
      ),
      enabled = listing != null && listing.error == null && !state.loading && !added,
    )
  }
}

/**
 * Termux home, phone storage and any SD cards as tabs. The tab holding the shown folder is
 * underlined; none is when the folder is outside all of them.
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
    val message = state.failure ?: listing?.error
    if (message != null) {
      item { Note(message, colors.destructive) }
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
private fun Note(text: String, color: Color) {
  Text(
    text = text,
    modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s12),
    color = color,
    fontSize = PipkinTokens.FontSize.sm,
  )
}

/**
 * One browser session for the pane. All calls run on the main thread. [close] may come while [open]
 * is still starting the host; the host is then closed as soon as it arrives.
 */
private class FolderBrowserState(
  private val hostClient: HostClient,
  private val scope: CoroutineScope,
  private val start: String,
) {
  var places by mutableStateOf(emptyList<FolderPlace>())
    private set
  var listing by mutableStateOf<FolderListing?>(null)
    private set
  var loading by mutableStateOf(true)
    private set
  var failure by mutableStateOf<String?>(null)
    private set

  private var browser: FolderBrowser? = null
  private var closed = false

  suspend fun open() = load {
    val opened = hostClient.openFolderBrowser()
    browser = opened
    if (closed) opened.close()
    places = opened.places
    opened.list(start)
  }

  fun navigate(path: String) {
    val current = browser ?: return
    scope.launch { load { current.list(path) } }
  }

  fun close() {
    closed = true
    browser?.close()
  }

  private suspend fun load(block: suspend () -> FolderListing) {
    loading = true
    failure = null
    try {
      listing = block()
    } catch (error: HostRejected) {
      failure = error.error
    } catch (error: IOException) {
      failure = error.message.orEmpty()
    } finally {
      loading = false
    }
  }
}
