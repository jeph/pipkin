package io.jeph.pipkin.terminal

import android.content.Context
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.data.folderKey
import io.jeph.pipkin.host.HostClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The open terminals, which belong to a folder rather than a chat: every chat in a folder shows the same ones, as
 * Paseo's workspace tabs do. Main-thread confined.
 */
internal class TerminalRegistry(
  private val context: Context,
  private val hostClient: HostClient,
  private val scope: CoroutineScope,
) {
  private val sessionsFlow = MutableStateFlow<List<TerminalSession>>(emptyList())
  private val shownFlow = MutableStateFlow<Shown?>(null)
  private var nextId = 1

  val sessions: StateFlow<List<TerminalSession>> = sessionsFlow.asStateFlow()

  /** The terminal the workspace shows instead of [Shown.chat]; null or another chat shows the chat. */
  val shown: StateFlow<Shown?> = shownFlow.asStateFlow()

  /** Starts a shell in [chat]'s folder and shows it in place of the chat. */
  fun open(chat: AgentProcess) {
    val folder = folderKey(chat.cwd)
    val number = (sessionsFlow.value.filter { it.cwd == folder }.maxOfOrNull { it.number } ?: 0) + 1
    val session = TerminalSession(context, nextId++, number, folder, hostClient, scope)
    session.start()
    sessionsFlow.update { it + session }
    show(chat, session)
  }

  /** Shows [session] in place of [chat], or the chat itself when [session] is null. */
  fun show(chat: AgentProcess, session: TerminalSession?) {
    shownFlow.value = session?.let { Shown(chat, it) }
  }

  fun close(session: TerminalSession) {
    session.close()
    sessionsFlow.update { it - session }
    shownFlow.update { if (it?.session == session) null else it }
  }

  data class Shown(val chat: AgentProcess, val session: TerminalSession)
}
