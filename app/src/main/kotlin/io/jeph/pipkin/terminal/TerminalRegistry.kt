package io.jeph.pipkin.terminal

import android.content.Context
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.workspace.ShownTab
import io.jeph.pipkin.workspace.WorkspaceTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * The open terminals, which belong to a folder rather than a chat: every chat in a folder shows the same ones, as
 * Paseo's workspace tabs do. Main-thread confined.
 */
internal class TerminalRegistry(
  private val context: Context,
  private val hostClient: HostClient,
  private val scope: CoroutineScope,
  private val shownTab: ShownTab,
) {
  private val sessionsFlow = MutableStateFlow<List<TerminalSession>>(emptyList())
  private var nextId = 1

  val sessions: StateFlow<List<TerminalSession>> = sessionsFlow.asStateFlow()

  /** Whether any open terminal has commands or jobs running. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val busy: StateFlow<Boolean> = sessionsFlow
    .flatMapLatest { sessions ->
      if (sessions.isEmpty()) flowOf(false) else combine(sessions.map { it.busy }) { states -> states.any { it } }
    }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /** Starts a shell in [chat]'s folder and shows it in place of the chat. */
  fun open(chat: AgentProcess) {
    val folder = folderKey(chat.cwd)
    val number = (sessionsFlow.value.filter { it.cwd == folder }.maxOfOrNull { it.number } ?: 0) + 1
    val session = TerminalSession(context, nextId++, number, folder, hostClient, scope)
    session.start()
    sessionsFlow.update { it + session }
    shownTab.show(chat, WorkspaceTab.Terminal(session))
  }

  fun close(session: TerminalSession) {
    session.close()
    sessionsFlow.update { it - session }
    shownTab.forget(WorkspaceTab.Terminal(session))
  }
}
