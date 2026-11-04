package io.jeph.pipkin.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.AppStrings
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.host.RemoteHostsState
import io.jeph.pipkin.ui.ShellActions

/** The remote folder a stand-in stands for: [path] on host [hostId], which is [host] or null once it was removed. */
internal data class RemoteFolder(val hostId: String, val path: String, val host: RemoteHost?)

/**
 * How workspace folders are shown. A folder on the phone is named after its last segment, `~` for the Termux [home],
 * and its path starts with `~` under that home. A stand-in folder shows as the remote folder it stands for: its name
 * the same way against the host's home, and its path after the host's name, `devbox · ~/src/api`, or after
 * [removedHost] once the host is gone. [separator] goes between the host and the path.
 */
internal class FolderNames(
  val home: String,
  private val remotes: RemoteHostsState,
  private val removedHost: String,
  private val separator: String,
) {
  private val standIns = remotes.workspaces.associateBy { folderKey(it.cwd) }

  /** The remote folder [cwd] stands for, or null for a folder on the phone. */
  fun remote(cwd: String): RemoteFolder? {
    val workspace = standIns[folderKey(cwd)] ?: return null
    return RemoteFolder(workspace.hostId, workspace.path, remotes.host(workspace.hostId))
  }

  fun isRemote(cwd: String): Boolean = folderKey(cwd) in standIns

  /** The workspace's name: its folder's, or `~` for a home folder. */
  fun name(cwd: String): String {
    val remote = remote(cwd) ?: return projectName(cwd, home)
    return remoteName(remote.path, remote.host?.home)
  }

  /**
   * The path of [subfolder] (relative, empty for the folder itself) in the workspace [cwd], with `~` for the home
   * folder, after the host's name for a remote one.
   */
  fun path(cwd: String, subfolder: String = ""): String {
    val remote = remote(cwd) ?: return tildePath(joinPath(cwd, subfolder), home)
    val host = remote.host?.name ?: removedHost
    return host + separator + remotePath(joinPath(remote.path, subfolder), remote.host?.home)
  }

  /** The absolute path of [relative] in the workspace [cwd], on the remote for a stand-in: what Copy path copies. */
  fun absolutePath(cwd: String, relative: String): String = joinPath(remote(cwd)?.path ?: cwd, relative)

  /** The stand-in folder of [path] on host [hostId], if there is one yet. */
  fun standIn(hostId: String, path: String): String? =
    remotes.workspaces.firstOrNull { it.hostId == hostId && folderKey(it.path) == folderKey(path) }?.cwd

  /** How the folder picker shows the folders of [host], or of the phone when it is null. */
  fun picker(host: RemoteHost?): PickerFolders = PickerFolders(host, home, separator)
}

/**
 * How the folder picker shows folders of the machine it browses: the phone's against the Termux [home], those of a
 * remote [host] after its name and with `~` for its home. [separator] goes between the host and the path.
 */
internal class PickerFolders(private val host: RemoteHost?, private val home: String, private val separator: String) {
  fun name(path: String): String = if (host == null) projectName(path, home) else remoteName(path, host.home)

  fun path(path: String): String =
    if (host == null) tildePath(path, home) else host.name + separator + remotePath(path, host.home)
}

/** [path] on a remote host whose home folder is [home] (null before the first connect), with `~` for that home. */
internal fun remotePath(path: String, home: String?): String = if (home.isNullOrEmpty()) path else tildePath(path, home)

/** The name of the folder [path] on a remote host whose home folder is [home]: its last segment, or `~` for home. */
internal fun remoteName(path: String, home: String?): String =
  if (home.isNullOrEmpty()) lastSegment(path) else projectName(path, home)

/** [relative] inside [folder]; [folder] itself when [relative] is empty. */
internal fun joinPath(folder: String, relative: String): String = when {
  relative.isEmpty() -> folder
  folder.endsWith('/') -> folder + relative
  else -> "$folder/$relative"
}

private fun lastSegment(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

/** [FolderNames] for what the app knows now, for code without composition such as notifications. */
internal fun AppGraph.folderNames(): FolderNames = FolderNames(
  home = hostClient.lastHello.value?.home?.takeIf { it.isNotEmpty() } ?: ShellActions.TERMUX_HOME,
  remotes = remoteHosts.state.value,
  removedHost = AppStrings.get(R.string.remote_host_removed),
  separator = AppStrings.get(R.string.separator_dot),
)

/** [FolderNames] that follow the Termux home, the remote hosts and the app's language. */
@Composable
internal fun rememberFolderNames(): FolderNames {
  val graph = LocalContext.current.appGraph
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val remotes by graph.remoteHosts.state.collectAsStateWithLifecycle()
  val home = hello?.home?.takeIf { it.isNotEmpty() } ?: ShellActions.TERMUX_HOME
  val removedHost = stringResource(R.string.remote_host_removed)
  val separator = stringResource(R.string.separator_dot)
  return remember(home, remotes, removedHost, separator) { FolderNames(home, remotes, removedHost, separator) }
}
