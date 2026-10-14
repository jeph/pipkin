package io.jeph.pipkin.terminal

import android.content.Context
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.workspace.OpenTabs
import io.jeph.pipkin.workspace.Pane
import io.jeph.pipkin.workspace.WorkspaceTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Opens terminals, which belong to a folder rather than a chat: every chat in a folder shows the same ones, as Paseo's
 * workspace tabs do. [tabs] holds and closes them. Main-thread confined.
 */
internal class TerminalRegistry(
  private val context: Context,
  private val hostClient: HostClient,
  private val scope: CoroutineScope,
  private val tabs: OpenTabs,
) {
  private var nextId = 1

  /** Whether any open terminal has commands or jobs running. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val busy: StateFlow<Boolean> = tabs.tabs
    .flatMapLatest { open ->
      val sessions = open.terminals()
      if (sessions.isEmpty()) flowOf(false) else combine(sessions.map { it.busy }) { states -> states.any { it } }
    }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /** Starts a shell in the folder [cwd] and shows it in [pane]. */
  fun open(pane: Pane, cwd: String) {
    val folder = folderKey(cwd)
    val number = (tabs.tabs.value.terminals().filter { it.cwd == folder }.maxOfOrNull { it.number } ?: 0) + 1
    val session = TerminalSession(context, nextId++, number, folder, hostClient, scope)
    session.start()
    tabs.show(pane, WorkspaceTab.Terminal(session))
  }

  private fun List<WorkspaceTab>.terminals(): List<TerminalSession> =
    filterIsInstance<WorkspaceTab.Terminal>().map { it.session }
}
