package io.jeph.pipkin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.workspaces
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.components.DialogConfirm
import io.jeph.pipkin.ui.components.PipkinDialog
import io.jeph.pipkin.ui.sidebar.RemovalCounts
import io.jeph.pipkin.ui.sidebar.rememberFolderNames
import io.jeph.pipkin.ui.sidebar.removalStops
import java.io.IOException
import org.json.JSONException

/**
 * Asks before removing [host], saying how many of its workspaces leave the sidebar and what happens to their chats, as
 * removing one workspace does; [onRemove] gets those workspaces' folders. The counts, and the chats removing archives,
 * come from a list of chats read when the dialog opens: a list not read yet, or one that failed, would leave chats
 * unarchived to come back as workspaces, so Remove waits for it and stays off when it fails.
 */
@Composable
internal fun RemoveHostDialog(
  host: RemoteHost,
  actions: ShellActions,
  onDismiss: () -> Unit,
  onRemove: (cwds: List<String>) -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val listing = rememberFreshListing()
  val sessions by graph.sessionArchive.active.collectAsStateWithLifecycle()
  val added by graph.preferences.workspaces.collectAsStateWithLifecycle()
  val names = rememberFolderNames()
  val hostWorkspaces = workspaces(sessions, added).filter { names.remote(it.cwd)?.hostId == host.id }
  val parts = mutableListOf(stringResource(R.string.remote_remove_text))
  when {
    listing is FreshListing.Failed -> parts += listing.error

    listing == FreshListing.Reading -> parts += stringResource(R.string.remote_remove_counting)

    hostWorkspaces.isNotEmpty() -> {
      val counts = RemovalCounts.of(actions, hostWorkspaces)
      parts += pluralStringResource(R.plurals.remote_remove_workspaces, hostWorkspaces.size, hostWorkspaces.size)
      if (counts.chats > 0) parts += pluralStringResource(R.plurals.remote_remove_chats, counts.chats, counts.chats)
      parts += removalStops(counts)
    }
  }
  PipkinDialog(
    title = stringResource(R.string.remote_remove_title, host.name),
    text = parts.joinToString(" "),
    icon = R.drawable.ic_lucide_trash_2,
    confirm = DialogConfirm(
      label = stringResource(R.string.remote_remove),
      onClick = {
        onDismiss()
        onRemove(hostWorkspaces.map { it.cwd })
      },
      enabled = listing == FreshListing.Read,
      destructive = true,
    ),
    dismissLabel = stringResource(R.string.remote_cancel),
    onDismiss = onDismiss,
  )
}

/** Whether the list of chats read for [RemoveHostDialog] is in. */
private sealed interface FreshListing {
  data object Reading : FreshListing

  data object Read : FreshListing

  data class Failed(val error: String) : FreshListing
}

/** Reads the list of chats afresh once, for the calling dialog. */
@Composable
private fun rememberFreshListing(): FreshListing {
  val repository = LocalContext.current.appGraph.sessionRepository
  var listing by remember { mutableStateOf<FreshListing>(FreshListing.Reading) }
  LaunchedEffect(repository) {
    listing = try {
      repository.listNow()
      FreshListing.Read
    } catch (rejected: HostRejected) {
      FreshListing.Failed(rejected.error)
    } catch (failure: IOException) {
      FreshListing.Failed(failure.message.orEmpty())
    } catch (failure: JSONException) {
      FreshListing.Failed(failure.message.orEmpty())
    }
  }
  return listing
}
