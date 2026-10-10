package io.jeph.pipkin.agent

import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What [AgentRegistry] needs from one chat's agent process. */
internal interface ManagedProcess {
  val cwd: String

  /** The session file Pi reported, or the path the process was opened with until then. */
  val sessionFile: StateFlow<String?>

  val busy: StateFlow<Boolean>

  suspend fun abort()

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

/** Opening another chat would need a fifth Pi process while all four are working. */
internal class TooManyRunning :
  IllegalStateException("${AgentRegistry.MAX_PROCESSES} chats are working. Stop one to open another.")

/**
 * The app's open agent processes, at most [MAX_PROCESSES]. A process that is neither busy nor
 * visible is closed after [idleCloseMillis]; opening one more evicts the least-recently-visible idle
 * process. Main thread only.
 */
internal class AgentRegistry<P : ManagedProcess>(
  private val scope: CoroutineScope,
  private val idleCloseMillis: Long = IDLE_CLOSE_MILLIS,
  private val factory: (cwd: String, sessionPath: String?) -> P,
) {
  private class Entry(var lastVisible: Long) {
    var watcher: Job? = null
  }

  private val entries = IdentityHashMap<P, Entry>()
  private val processState = MutableStateFlow<Map<ChatKey, P>>(emptyMap())
  private val visibleState = MutableStateFlow<P?>(null)
  private val busyState = MutableStateFlow(false)
  private var tick = 0L

  /** Open processes by chat; a new chat is rekeyed to its session file once Pi reports it. */
  val processes: StateFlow<Map<ChatKey, P>> = processState.asStateFlow()

  /** The process the chat panel shows; follows [reload] and clears when that process closes. */
  val visible: StateFlow<P?> = visibleState.asStateFlow()

  /** True while any open process is working. */
  val busy: StateFlow<Boolean> = busyState.asStateFlow()

  fun keyOf(process: P): ChatKey? = processState.value.entries.firstOrNull { it.value === process }?.key

  /**
   * The open process for [sessionPath], or a new one (a new session when [sessionPath] is null).
   * Throws [TooManyRunning] when all [MAX_PROCESSES] processes are busy.
   */
  fun open(cwd: String, sessionPath: String?): P {
    sessionPath?.let { processState.value[ChatKey.session(it)] }?.let { return it }
    if (entries.size >= MAX_PROCESSES) evictOne()
    val process = factory(cwd, sessionPath)
    add(if (sessionPath != null) ChatKey.session(sessionPath) else ChatKey.new(), process)
    return process
  }

  /** Marks [process] as the one on screen, or none; processes this registry does not hold count as none. */
  fun setVisible(process: P?) {
    val previous = visibleState.value
    if (previous === process) return
    tick += 1
    if (previous != null) entries[previous]?.lastVisible = tick
    val next = process?.takeIf { entries.containsKey(it) }
    if (next != null) entries[next]?.lastVisible = tick
    visibleState.value = next
  }

  /** Closes [process] and opens a fresh Pi on the same cwd and session file under the same key. */
  fun reload(process: P): P {
    val sessionPath = process.sessionFile.value
    val key = keyOf(process) ?: return open(process.cwd, sessionPath)
    val wasVisible = visibleState.value === process
    remove(process)
    val replacement = factory(process.cwd, sessionPath)
    add(key, replacement)
    if (wasVisible) setVisible(replacement)
    return replacement
  }

  /**
   * Applies changed Pi configuration to every idle process: the visible one is reloaded; hidden
   * ones are closed, since they reopen with the new configuration when shown. Busy processes are untouched.
   */
  fun reloadIdle() {
    for (process in processState.value.values.toList()) {
      if (process.busy.value) continue
      if (visibleState.value === process) reload(process) else remove(process)
    }
  }

  /**
   * The notification's Stop action: aborts every working process. A process that cannot abort is
   * ended so Stop always stops: the visible one restarts on its history, hidden ones close.
   */
  fun abortAll() {
    for (process in processState.value.values) {
      if (process.busy.value) scope.launch { abortOrEnd(process) }
    }
  }

  /** Closes and forgets [process] (for example before its session is deleted). */
  fun close(process: P) = remove(process)

  private suspend fun abortOrEnd(process: P) {
    try {
      process.abort()
    } catch (_: RpcError) {
      // Being visible implies being held; remove ignores a process that already left.
      if (visibleState.value === process) reload(process) else remove(process)
    }
  }

  private fun evictOne() {
    val shown = visibleState.value
    val idle = entries.entries
      .filter { !it.key.busy.value }
      .minByOrNull { if (it.key === shown) Long.MAX_VALUE else it.value.lastVisible }
      ?: throw TooManyRunning()
    remove(idle.key)
  }

  private fun add(key: ChatKey, process: P) {
    val entry = Entry(++tick)
    entries[process] = entry
    processState.value += key to process
    // Registered before the watcher starts: an immediate dispatcher runs it inline.
    entry.watcher = scope.launch { watch(process) }
  }

  private fun remove(process: P) {
    val entry = entries.remove(process) ?: return
    processState.value = processState.value.filterValues { it !== process }
    if (visibleState.value === process) visibleState.value = null
    updateBusy()
    process.close()
    entry.watcher?.cancel()
  }

  private suspend fun watch(process: P) = coroutineScope {
    launch { process.busy.collect { updateBusy() } }
    launch {
      process.sessionFile.collect { path -> if (path != null) rekey(process, ChatKey.session(path)) }
    }
    combine(process.busy, visibleState) { working, shown -> !working && shown !== process }
      .distinctUntilChanged()
      .collectLatest { idle ->
        if (idle) {
          delay(idleCloseMillis)
          remove(process)
        }
      }
  }

  private fun rekey(process: P, key: ChatKey) {
    val current = keyOf(process) ?: return
    if (current == key || processState.value.containsKey(key)) return
    processState.value = processState.value - current + (key to process)
  }

  private fun updateBusy() {
    busyState.value = entries.keys.any { it.busy.value }
  }

  companion object {
    const val MAX_PROCESSES = 4
    const val IDLE_CLOSE_MILLIS = 60_000L
  }
}
