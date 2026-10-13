package io.jeph.pipkin.workspace

import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.files.FileTab
import io.jeph.pipkin.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A workspace tab other than a chat, which the workspace shows in place of its chat. */
internal sealed interface WorkspaceTab {
  data class Terminal(val session: TerminalSession) : WorkspaceTab

  data class File(val file: FileTab) : WorkspaceTab
}

/**
 * The one tab the workspace shows in place of a chat, so a terminal and a file can't both claim the chat's place.
 * Main-thread confined.
 */
internal class ShownTab {
  private val shownFlow = MutableStateFlow<Shown?>(null)

  /** The tab shown instead of [Shown.chat]; null or another chat shows the chat. */
  val shown: StateFlow<Shown?> = shownFlow.asStateFlow()

  /** Shows [tab] in place of [chat], or the chat itself when [tab] is null. */
  fun show(chat: AgentProcess, tab: WorkspaceTab?) {
    shownFlow.value = tab?.let { Shown(chat, it) }
  }

  /** Shows the chat again if [tab], which is closing, is the one shown. */
  fun forget(tab: WorkspaceTab) {
    shownFlow.update { if (it?.tab == tab) null else it }
  }

  data class Shown(val chat: AgentProcess, val tab: WorkspaceTab)
}
