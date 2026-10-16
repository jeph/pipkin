package io.jeph.pipkin.agent

import io.jeph.pipkin.workspace.Pane
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** What [AgentRegistry] needs from one chat's agent process. */
internal interface ManagedProcess {
  val cwd: String

  /** The session file Pi reported, or the path the process was opened with until then. */
  val sessionFile: StateFlow<String?>

  val busy: StateFlow<Boolean>

  suspend fun abort()

  /** Ends Pi but keeps the chat's history on screen, for [restart] to reload once Pi is back. */
  fun park()

  /** Starts Pi again on the same cwd and session file, keeping the history until Pi has sent it again. */
  fun restart()

  /** Final: ends Pi and releases the process. */
  fun close()
}

/** A chat's identity in [AgentRegistry]: its session file path once known, otherwise `new:<uuid>`. */
@JvmInline
internal value class ChatKey(val value: String) {
  companion object {
    fun session(path: String) = ChatKey(path)

    fun new() = ChatKey("new:${UUID.randomUUID()}")
  }
}

/**
 * Opening another chat would need a Pi process past [limit] while not enough of the [open] ones are idle and hidden,
 * since a chat a pane shows keeps its Pi. [shownIdle] says an idle chat in a pane held a slot. [open] is above [limit]
 * after the limit was lowered while chats were working.
 */
internal class TooManyRunning(open: Int, limit: Int, shownIdle: Boolean = false) :
  IllegalStateException(
    "$open chats are ${if (shownIdle) "working or on screen" else "working"}. " +
      if (open > limit) {
        "That's more than the limit of $limit, so stop some to open another."
      } else {
        "Stop one to open another."
      },
  )

/**
 * The app's open agent processes, at most [maxRunning]. A process that is neither busy nor shown in a pane is parked
 * after [idleParkMillis], never while that is null, and opening one more parks the idle process shown least recently,
 * never one a pane shows: its Pi ends, but the chat and its history are kept, so opening its session again shows the
 * history while Pi restarts. A changed idle delay counts from the change. Lowering [maxRunning] parks hidden idle
 * processes down to it at once, and the rest as runs end or more chats open. At most [MAX_PARKED] chats stay parked;
 * the least recently parked is closed for good. [onRunEnded] hears each time an open process stops being busy. Main
 * thread only.
 */
internal class AgentRegistry<P : ManagedProcess>(
  private val scope: CoroutineScope,
  private val maxRunning: StateFlow<Int> = MutableStateFlow(DEFAULT_MAX_RUNNING),
  private val idleParkMillis: Flow<Long?> = flowOf(IdleStop.Default.millis),
  private val onRunEnded: (P) -> Unit = {},
  private val factory: (cwd: String, sessionPath: String?) -> P,
) {
  private class Entry(var lastShown: Long) {
    var watcher: Job? = null
  }

  private val entries = IdentityHashMap<P, Entry>()
  private val processState = MutableStateFlow<Map<ChatKey, P>>(emptyMap())
  private val shownState = MutableStateFlow<Map<Pane, P>>(emptyMap())
  private val busyState = MutableStateFlow(false)
  private var tick = 0L

  /** Parked chats by session file, least recently parked first. */
  private val parked = LinkedHashMap<ChatKey, P>()

  /** Open processes by chat; a new chat is rekeyed to its session file once Pi reports it. */
  val processes: StateFlow<Map<ChatKey, P>> = processState.asStateFlow()

  /**
   * The process each pane shows; a pane follows [reload] and empties when its process closes. No process shows in two
   * panes.
   */
  val shown: StateFlow<Map<Pane, P>> = shownState.asStateFlow()

  /** True while any open process is working. */
  val busy: StateFlow<Boolean> = busyState.asStateFlow()

  init {
    scope.launch { maxRunning.collect { parkPastLimit() } }
  }

  fun keyOf(process: P): ChatKey? = processState.value.entries.firstOrNull { it.value === process }?.key

  /**
   * The open process for [sessionPath], the parked one restarted, or a new one (a new session when [sessionPath] is
   * null). Throws [TooManyRunning] when [maxRunning] processes are open and all of them are busy or shown.
   */
  fun open(cwd: String, sessionPath: String?): P {
    val key = sessionPath?.let { ChatKey.session(it) }
    key?.let { processState.value[it] }?.let { return it }
    // Taken out first, so making room cannot close it as the least recently parked chat.
    val resumed = key?.let { parked.remove(it) }
    // All or nothing, so a refused open parks no chat: a pane hides its chat while the next one opens and shows it
    // again when that is refused. A lowered limit can leave more than one process to park.
    val evictable = hiddenIdleByLastShown()
    val excess = entries.size - maxRunning.value + 1
    if (excess > evictable.size) {
      if (key != null && resumed != null) parked[key] = resumed
      throw TooManyRunning(entries.size, maxRunning.value, shownIdle = entries.keys.any { !it.busy.value })
    }
    evictable.take(excess.coerceAtLeast(0)).forEach(::park)
    val process = resumed?.also { it.restart() } ?: factory(cwd, sessionPath)
    add(key ?: ChatKey.new(), process)
    return process
  }

  /**
   * Shows [process] in [pane], or nothing; a process this registry does not hold counts as nothing. Returns false,
   * changing nothing, when another pane shows [process].
   */
  fun show(pane: Pane, process: P?): Boolean {
    val panes = shownState.value
    val previous = panes[pane]
    if (previous === process) return true
    if (process != null && panes[pane.other] === process) return false
    tick += 1
    if (previous != null) entries[previous]?.lastShown = tick
    val next = process?.takeIf { entries.containsKey(it) }
    if (next != null) entries[next]?.lastShown = tick
    shownState.value = if (next == null) panes - pane else panes + (pane to next)
    return true
  }

  /** The pane showing [process], if one does. */
  fun paneOf(process: P): Pane? = shownState.value.entries.firstOrNull { it.value === process }?.key

  /** Closes [process] and opens a fresh Pi on the same cwd and session file under the same key, in the same pane. */
  fun reload(process: P): P {
    val sessionPath = process.sessionFile.value
    val key = keyOf(process) ?: return open(process.cwd, sessionPath)
    val pane = paneOf(process)
    remove(process)
    val replacement = factory(process.cwd, sessionPath)
    add(key, replacement)
    if (pane != null) show(pane, replacement)
    return replacement
  }

  /**
   * Applies changed Pi configuration to every idle process: shown ones are reloaded; hidden ones are parked, since
   * they restart with the new configuration when shown. Busy processes are untouched.
   */
  fun reloadIdle() {
    for (process in processState.value.values.toList()) {
      if (process.busy.value) continue
      if (paneOf(process) != null) reload(process) else park(process)
    }
  }

  /**
   * The notification's Stop action: aborts every working process. A process that cannot abort is
   * ended so Stop always stops: a shown one restarts on its history, hidden ones close.
   */
  fun abortAll() {
    for (process in processState.value.values) {
      if (process.busy.value) scope.launch { abortOrEnd(process) }
    }
  }

  /**
   * Ends every chat's Pi so its configuration can be replaced, aborting working chats first. Hidden chats are parked;
   * shown ones keep their history on screen until [restartShown] starts their Pi again.
   */
  suspend fun stopAll() {
    for (process in processState.value.values.toList()) {
      if (process.busy.value) {
        try {
          process.abort()
        } catch (_: RpcError) {
          // Its Pi ends next either way.
        }
      }
      if (paneOf(process) != null) process.park() else park(process)
    }
  }

  /** Starts Pi again for every chat a pane shows, after [stopAll]. */
  fun restartShown() {
    for (process in shownState.value.values) process.restart()
  }

  /** Closes and forgets [process] (for example before its session is deleted). */
  fun close(process: P) = remove(process)

  /** Closes the parked chats for [sessionPaths] (for example once they are archived), so none reopens or lingers. */
  fun discardParked(sessionPaths: Collection<String>) {
    for (path in sessionPaths) parked.remove(ChatKey.session(path))?.close()
  }

  private suspend fun abortOrEnd(process: P) {
    try {
      process.abort()
    } catch (_: RpcError) {
      // Being shown implies being held; remove ignores a process that already left.
      if (paneOf(process) != null) reload(process) else remove(process)
    }
  }

  /** Parks hidden idle processes, least recently shown first, until [maxRunning] or fewer are open or none is left. */
  private fun parkPastLimit() {
    hiddenIdleByLastShown().take((entries.size - maxRunning.value).coerceAtLeast(0)).forEach(::park)
  }

  /** The processes that are neither busy nor shown, which may be parked to make room; least recently shown first. */
  private fun hiddenIdleByLastShown(): List<P> = entries.entries
    .filter { !it.key.busy.value && paneOf(it.key) == null }
    .sortedBy { it.value.lastShown }
    .map { it.key }

  private fun add(key: ChatKey, process: P) {
    val entry = Entry(++tick)
    entries[process] = entry
    processState.value += key to process
    // Registered before the watcher starts: an immediate dispatcher runs it inline.
    entry.watcher = scope.launch { watch(process) }
  }

  private fun remove(process: P) {
    if (detach(process)) process.close()
  }

  /** Ends [process]'s Pi and keeps the chat for [open]; a chat without its own session file has nothing to keep. */
  private fun park(process: P) {
    val key = keyOf(process)
    if (!detach(process)) return
    val path = process.sessionFile.value
    if (key == null || path == null || key != ChatKey.session(path)) {
      process.close()
      return
    }
    process.park()
    parked[key] = process
    if (parked.size > MAX_PARKED) parked.remove(parked.keys.first())?.close()
  }

  /** Stops tracking [process] without ending it; false when this registry does not hold it. */
  private fun detach(process: P): Boolean {
    val entry = entries.remove(process) ?: return false
    processState.value = processState.value.filterValues { it !== process }
    if (paneOf(process) != null) shownState.value = shownState.value.filterValues { it !== process }
    updateBusy()
    entry.watcher?.cancel()
    return true
  }

  private suspend fun watch(process: P) = coroutineScope {
    launch {
      var wasBusy = false
      process.busy.collect { busy ->
        updateBusy()
        if (wasBusy && !busy) {
          onRunEnded(process)
          // A limit lowered while chats were working applies as their runs end.
          parkPastLimit()
        }
        wasBusy = busy
      }
    }
    launch {
      process.sessionFile.collect { path -> if (path != null) rekey(process, ChatKey.session(path)) }
    }
    // How long until the process parks: null while it works, a pane shows it, or idle chats keep their Pi.
    combine(process.busy, shownState, idleParkMillis) { working, panes, millis ->
      if (working || panes.values.any { it === process }) null else millis
    }
      .distinctUntilChanged()
      .collectLatest { millis ->
        if (millis == null) return@collectLatest
        delay(millis)
        park(process)
      }
  }

  private fun rekey(process: P, key: ChatKey) {
    val current = keyOf(process) ?: return
    if (current == key || processState.value.containsKey(key)) return
    processState.value = processState.value - current + (key to process)
    // A live chat took over a parked chat's session (Pi's /resume), so that copy's history is stale.
    parked.remove(key)?.close()
  }

  private fun updateBusy() {
    busyState.value = entries.keys.any { it.busy.value }
  }

  companion object {
    /** The choices for [maxRunning]; at least two, so split screen can show a chat in each pane. */
    val RUNNING_LIMITS = 2..16
    const val DEFAULT_MAX_RUNNING = 6
    const val MAX_PARKED = 12
  }
}
