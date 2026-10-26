package io.jeph.pipkin.workspace

import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.ManagedProcess
import io.jeph.pipkin.agent.TooManyRunning
import io.jeph.pipkin.data.sameFolder
import io.jeph.pipkin.files.FileTab
import io.jeph.pipkin.git.CommitTab
import io.jeph.pipkin.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A workspace tab other than a chat, which a pane shows instead of a chat. [OpenTabs] holds the open ones, so a new kind
 * lists, counts, swaps, goes back and closes like the others; the UI asks for its row, screen and actions through
 * exhaustive `when`s over this interface.
 */
internal sealed interface WorkspaceTab {
  /** The tab's workspace folder. */
  val cwd: String

  /** Unique among open tabs and saveable, for list keys. */
  val key: String

  /** Lets go of what the tab holds, once it has closed. */
  fun release()

  data class Terminal(val session: TerminalSession) : WorkspaceTab {
    override val cwd: String get() = session.cwd
    override val key: String get() = "terminal-${session.id}"

    override fun release() = session.close()
  }

  data class File(val file: FileTab) : WorkspaceTab {
    override val cwd: String get() = file.cwd
    override val key: String get() = "file-${file.id}"

    override fun release() = Unit
  }

  data class Commit(val commit: CommitTab) : WorkspaceTab {
    override val cwd: String get() = commit.cwd
    override val key: String get() = "commit-${commit.id}"

    override fun release() = Unit
  }
}

/** What a pane shows: a chat, or a terminal, file or commit. */
internal sealed interface PaneItem<out P : ManagedProcess> {
  /** The item's workspace folder. */
  val cwd: String

  data class Chat<out P : ManagedProcess>(val process: P) : PaneItem<P> {
    override val cwd: String get() = process.cwd
  }

  data class Tab(val tab: WorkspaceTab) : PaneItem<Nothing> {
    override val cwd: String get() = tab.cwd
  }
}

/**
 * What each pane shows and showed before. A pane shows one item: a chat, which [registry] holds as the pane's chat, or
 * a tab, which leaves the pane without one. No item shows in both panes. Each pane's history keeps the items it showed
 * before, newest last, for Back; an item is in at most one history, and never while a pane shows it, so going back
 * never takes an item from the other pane. Main thread only.
 */
internal class Panes<P : ManagedProcess>(
  private val registry: AgentRegistry<P>,
  private val isArchived: (sessionPath: String) -> Boolean,
) {
  private val tabsFlow = MutableStateFlow<Map<Pane, WorkspaceTab>>(emptyMap())
  private val historyFlow = MutableStateFlow<Map<Pane, List<PaneItem<P>>>>(emptyMap())

  /** By pane, the tab it shows instead of a chat. */
  val tabs: StateFlow<Map<Pane, WorkspaceTab>> = tabsFlow.asStateFlow()

  /** By pane, the items it showed before, newest last. */
  val history: StateFlow<Map<Pane, List<PaneItem<P>>>> = historyFlow.asStateFlow()

  fun itemIn(pane: Pane): PaneItem<P>? = itemIn(tabsFlow.value, registry.shown.value, pane)

  /**
   * Shows [tab] in [pane] instead of what it shows, which its history keeps unless [remember] is false. Returns false,
   * changing nothing, when the other pane shows [tab].
   */
  fun showTab(pane: Pane, tab: WorkspaceTab, remember: Boolean = true): Boolean {
    if (tabsFlow.value[pane.other] == tab) return false
    val previous = itemIn(pane)
    val item = PaneItem.Tab(tab)
    if (previous == item) return true
    // The tab before the chat leaves, so the pane never shows nothing in between, which would close split screen.
    tabsFlow.update { it + (pane to tab) }
    registry.show(pane, null)
    record(pane, previous.takeIf { remember }, item)
    return true
  }

  /**
   * Records that [pane] shows [chat], which [registry] already shows there, instead of [previous], the item it showed
   * before, which its history keeps.
   */
  fun chatShown(pane: Pane, chat: P, previous: PaneItem<P>?) {
    tabsFlow.update { it - pane }
    record(pane, previous, PaneItem.Chat(chat))
  }

  /**
   * Shows the newest item in [pane]'s history that can still show, dropping those passed over: chats archived since,
   * and closed ones without a session file to reopen. False when there is none.
   */
  fun back(pane: Pane): Boolean {
    while (true) {
      val item = historyFlow.value[pane]?.lastOrNull() ?: return false
      if (showFromHistory(pane, item, previous = null)) return true
    }
  }

  /**
   * Shows the newest item in [pane]'s history in the folder [cwd], dropping those passed over as [back] does; the
   * history keeps what the pane showed before. False when there is none.
   */
  fun showLastIn(pane: Pane, cwd: String): Boolean {
    while (true) {
      val item = historyFlow.value[pane]?.lastOrNull { sameFolder(it.cwd, cwd) } ?: return false
      if (showFromHistory(pane, item, itemIn(pane))) return true
    }
  }

  /** Forgets [tab], which is closing: a pane showing it goes back, or shows nothing when it has nothing to go back to. */
  fun forget(tab: WorkspaceTab) {
    val item = PaneItem.Tab(tab)
    historyFlow.update { histories -> histories.mapValues { (_, items) -> items - item } }
    val pane = tabsFlow.value.entries.firstOrNull { it.value == tab }?.key ?: return
    if (!back(pane)) tabsFlow.update { it - pane }
  }

  /** Forgets the chats in session files [paths], which are archived, from every history. */
  fun forgetChats(paths: Collection<String>) {
    historyFlow.update { histories ->
      histories.mapValues { (_, items) ->
        items.filterNot { it is PaneItem.Chat && it.process.sessionFile.value in paths }
      }
    }
  }

  /**
   * Closes split screen from [closing], forgetting its item and history: the other pane's item and history stay, in
   * the main pane, which the second pane's are moved to when [closing] is the main pane.
   */
  fun closeSplit(closing: Pane) {
    val kept = closing.other
    val chat = registry.shown.value[kept]
    tabsFlow.value = tabsFlow.value[kept]?.let { mapOf(Pane.Primary to it) }.orEmpty()
    historyFlow.value = historyFlow.value[kept]?.let { mapOf(Pane.Primary to it) }.orEmpty()
    // The second pane lets go of its chat first, since the registry shows a chat in one pane at most.
    registry.show(Pane.Secondary, null)
    registry.show(Pane.Primary, chat)
  }

  /** Takes [item] out of [pane]'s history and shows it there instead of [previous]; false when it can't show. */
  private fun showFromHistory(pane: Pane, item: PaneItem<P>, previous: PaneItem<P>?): Boolean {
    historyFlow.update { histories -> histories + (pane to histories[pane].orEmpty() - item) }
    return when (item) {
      is PaneItem.Tab -> showTab(pane, item.tab, remember = previous != null)
      is PaneItem.Chat -> showAgain(pane, item.process, previous)
    }
  }

  /** Shows [process] in [pane] again instead of [previous], reopening it from its session file once closed. */
  private fun showAgain(pane: Pane, process: P, previous: PaneItem<P>?): Boolean {
    val chat = reopen(process) ?: return false
    if (!registry.show(pane, chat)) return false
    chatShown(pane, chat, previous)
    return true
  }

  /**
   * [process] while it is open, else the process [registry] opens on its session file; null when archived, when
   * closed before Pi saved a session file, or when every running chat is working.
   */
  private fun reopen(process: P): P? {
    val path = process.sessionFile.value
    return when {
      path != null && isArchived(path) -> null

      registry.processes.value.values.any { it === process } -> process

      path == null -> null

      else -> try {
        registry.open(process.cwd, path)
      } catch (_: TooManyRunning) {
        null
      }
    }
  }

  /** Puts [previous] at the top of [pane]'s history and takes [shown], and [previous], out of every other place. */
  private fun record(pane: Pane, previous: PaneItem<P>?, shown: PaneItem<P>) {
    val kept = previous?.takeUnless { it.sameAs(shown) }
    historyFlow.update { histories ->
      Pane.entries.associateWith { each ->
        val items = histories[each].orEmpty().filterNot { it.sameAs(shown) || (kept != null && it.sameAs(kept)) }
        if (each == pane && kept != null) items + kept else items
      }.filterValues { it.isNotEmpty() }
    }
  }

  companion object {
    /** What [pane] shows, given every pane's [tabs] and [chats]; a tab wins while a chat is leaving for it. */
    fun <P : ManagedProcess> itemIn(tabs: Map<Pane, WorkspaceTab>, chats: Map<Pane, P>, pane: Pane): PaneItem<P>? =
      tabs[pane]?.let { PaneItem.Tab(it) } ?: chats[pane]?.let { PaneItem.Chat(it) }
  }
}

/** The same item: the same tab, or the same chat, though reloaded as another process. */
internal fun PaneItem<ManagedProcess>.sameAs(other: PaneItem<ManagedProcess>): Boolean = when (this) {
  is PaneItem.Tab -> this == other

  is PaneItem.Chat ->
    other is PaneItem.Chat &&
      (process === other.process || process.sessionFile.value?.let { it == other.process.sessionFile.value } == true)
}
