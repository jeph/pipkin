package io.jeph.pipkin.terminal

import android.content.Context
import android.util.Log
import io.github.sagernet.libghostty.GhosttyTerminalSession
import io.jeph.pipkin.host.HostChannel
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostFailure
import io.jeph.pipkin.host.HostProtocol
import io.jeph.pipkin.host.HostRejected
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal sealed interface TerminalState {
  data object Starting : TerminalState

  data object Running : TerminalState

  /** The shell ended; [code] is null when the connection dropped without the host's exit record. */
  data class Exited(val code: Int?) : TerminalState

  data class Failed(val message: String) : TerminalState
}

/**
 * One login shell in [cwd], run by the host's `terminal` mode and shown by libghostty's [terminal]. It lives in
 * [parentScope] until [close], so the shell keeps running while its tab is hidden.
 */
internal class TerminalSession(
  context: Context,
  val id: Int,
  /** The folder's number for this terminal, as in "Terminal 2", which it is called until it is renamed. */
  val number: Int,
  val cwd: String,
  private val hostClient: HostClient,
  parentScope: CoroutineScope,
) {
  private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

  /** Keystrokes and resizes, kept in order and held while no shell is connected. */
  private val outbound = Channel<JSONObject>(Channel.UNLIMITED)
  private val stateFlow = MutableStateFlow<TerminalState>(TerminalState.Starting)
  private var connection: Job? = null
  private val nameFlow = MutableStateFlow<String?>(null)
  private val busyFlow = MutableStateFlow(false)

  val state: StateFlow<TerminalState> = stateFlow.asStateFlow()

  /** The name the user gave this terminal; null shows "Terminal [number]". */
  val name: StateFlow<String?> = nameFlow.asStateFlow()

  /** Whether the host sees commands or jobs running in the shell, beyond the shell waiting at its prompt. */
  val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

  fun rename(name: String) {
    nameFlow.value = name
  }

  /**
   * Main-thread confined except [GhosttyTerminalSession.feedOutput]. It calls the transport from any thread, which
   * [Channel.trySend] allows without blocking.
   */
  val terminal = GhosttyTerminalSession(context, GhosttyTerminalSession.Options(DEFAULT_COLUMNS, DEFAULT_ROWS)).also {
    it.transport = object : GhosttyTerminalSession.Transport {
      override fun sendInput(data: ByteArray) {
        outbound.trySend(JSONObject().put("type", "input").put("data", Base64.getEncoder().encodeToString(data)))
      }

      override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
        outbound.trySend(JSONObject().put("type", "resize").put("rows", rows).put("cols", columns))
      }

      // The session owns the connection; [close] ends it.
      override fun close() = Unit
    }
  }

  /** Starts the shell, or a fresh one after it ended; the screen keeps the old one's output above it. */
  fun start() {
    connection?.cancel()
    while (outbound.tryReceive().isSuccess) {
      // Input typed for a shell that has gone would run in the new one.
    }
    stateFlow.value = TerminalState.Starting
    connection = scope.launch { connect() }
  }

  /** Ends the shell and frees the terminal; the session cannot start again. */
  fun close() {
    scope.cancel()
    outbound.close()
    terminal.close()
  }

  private suspend fun connect() {
    val columns = terminal.columns.takeIf { it > 0 } ?: DEFAULT_COLUMNS
    val rows = terminal.rows.takeIf { it > 0 } ?: DEFAULT_ROWS
    val channel = try {
      hostClient.openTerminal(cwd, rows, columns)
    } catch (failure: HostFailure) {
      stateFlow.value = TerminalState.Failed(failure.message.orEmpty())
      return
    }
    stateFlow.value = TerminalState.Running
    try {
      relay(channel)
    } finally {
      channel.close()
      busyFlow.value = false
    }
  }

  /** Feeds the shell's output to the terminal and sends [outbound] to the shell until the connection ends. */
  private suspend fun relay(channel: HostChannel) {
    var exit: JSONObject? = null
    coroutineScope {
      val writer = launch {
        try {
          for (message in outbound) channel.send(message)
        } catch (error: IOException) {
          Log.i(TAG, "Terminal $id stopped sending: ${error.message}")
        }
      }
      withContext(Dispatchers.Default) {
        channel.lines.collect { line ->
          when (line.optString("type")) {
            "output" -> {
              val bytes = Base64.getDecoder().decode(line.getString("data"))
              terminal.feedOutput(bytes)
            }

            HostProtocol.EXIT -> exit = line

            "busy" -> busyFlow.value = line.optBoolean("busy")

            HostProtocol.RESULT -> HostRejected.from(line)?.let { stateFlow.value = TerminalState.Failed(it.error) }
          }
        }
      }
      writer.cancel()
    }
    if (stateFlow.value != TerminalState.Running) return
    val code = exit?.let { if (it.isNull("code")) null else it.optInt("code") }
    stateFlow.value = TerminalState.Exited(code)
  }

  private companion object {
    const val TAG = "PipkinTerminal"

    /** The size a shell starts at before the terminal has been laid out; the first layout resizes it. */
    const val DEFAULT_COLUMNS = 80
    const val DEFAULT_ROWS = 24
  }
}
