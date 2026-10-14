package io.jeph.pipkin.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.FileContent
import io.jeph.pipkin.host.FileHost
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.workspace.OpenTabs
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.WorkspaceTab
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface FileState {
  data object Loading : FileState

  data class Loaded(val content: FileContent) : FileState

  data class Failed(val message: String) : FileState
}

/** One open file of the workspace folder [cwd], at the workspace-relative [path], which [read] reads from the host. */
internal class FileTab(
  val id: Int,
  val cwd: String,
  val path: String,
  private val read: suspend (cwd: String, path: String) -> FileContent,
) {
  private val stateFlow = MutableStateFlow<FileState>(FileState.Loading)

  val name: String get() = path.substringAfterLast('/')

  /** The folder holding the file, relative to the workspace; empty at its top. */
  val folder: String get() = path.substringBeforeLast('/', "")

  val state: StateFlow<FileState> = stateFlow.asStateFlow()

  /** Whether a markdown file shows rendered rather than as its source; Paseo opens markdown rendered. */
  var showsPreview by mutableStateOf(true)

  /** Reads the file again, since Pi may have changed it; what was read before stays shown until then. */
  suspend fun reload() {
    stateFlow.value = try {
      FileState.Loaded(read(cwd, path))
    } catch (error: HostRejected) {
      FileState.Failed(error.error)
    } catch (error: IOException) {
      FileState.Failed(error.message.orEmpty())
    }
  }
}

/**
 * Opens files, which belong to a folder rather than a chat, like terminals: every chat in a folder shows the same ones.
 * A file has at most one tab per folder. [tabs] holds and closes them. Main-thread confined.
 */
internal class FileTabRegistry(private val files: FileHost, private val tabs: OpenTabs) {
  private var nextId = 1

  /**
   * Shows [path]'s tab in the folder [cwd] in [pane], opening one when the file has none. Returns false, showing
   * nothing, when the other pane shows the file.
   */
  fun open(pane: Pane, cwd: String, path: String): Boolean = tabs.show(pane, tabFor(cwd, path))

  /** [path]'s tab in the folder [cwd]: the open one, else a new one, which isn't open until [OpenTabs] holds it. */
  fun tabFor(cwd: String, path: String): WorkspaceTab {
    val folder = folderKey(cwd)
    return tabs.tabs.value.firstOrNull { it is WorkspaceTab.File && it.cwd == folder && it.file.path == path }
      ?: WorkspaceTab.File(FileTab(nextId++, folder, path, files::read))
  }
}
