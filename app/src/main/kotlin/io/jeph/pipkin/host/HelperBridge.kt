package io.jeph.pipkin.host

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Starts hosts in Termux's Play build, which has no RUN_COMMAND service, through Pipkin's helper there. The helper runs
 * the same bash command RUN_COMMAND would and reports the child's exit on the connection that asked for it.
 *
 * A helper that runs another bundle than this APK's is updated before the launch: Pipkin sends the bundle, the helper
 * restarts with it, and the launch goes to the restarted helper. That costs one launch about a second after each app
 * update, but no launch ever lands on a helper that is just restarting, which would wake Termux for nothing.
 */
internal object HelperBridge {
  private const val TAG = "Pipkin"
  private const val CONNECT_TIMEOUT_MILLIS = 1_000
  private const val REPLY_TIMEOUT_MILLIS = 5_000
  private const val RESTART_TIMEOUT_MILLIS = 5_000L
  private const val RESTART_POLL_MILLIS = 100L
  private const val STDERR_LOG_LIMIT = 4096

  /** `128 + n` for the signals that end a host, as a shell reports them. */
  private val SIGNALS = mapOf(
    "SIGHUP" to 1,
    "SIGINT" to 2,
    "SIGQUIT" to 3,
    "SIGABRT" to 6,
    "SIGKILL" to 9,
    "SIGSEGV" to 11,
    "SIGPIPE" to 13,
    "SIGTERM" to 15,
  )

  private val loopback = InetAddress.getByName("127.0.0.1")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val updateLock = Mutex()

  /** Set once this process has offered its bundle; a helper that refused it isn't asked again until Pipkin restarts. */
  @Volatile
  private var updateOffered = false

  sealed interface Reach {
    class Connected(val key: ByteArray, val connection: HelperConnection) : Reach

    /** A helper answered on some port, paired with another key. */
    data object Rejected : Reach

    /** Something other than the helper holds every port. */
    data object PortsTaken : Reach

    /** Nothing answered: Termux or the helper isn't running. */
    data object Unreachable : Reach
  }

  /**
   * Asks the helper to run `bash [arguments]` with [stdin]. Before the host connects, the helper's report that the
   * command failed or exited goes to [onFailure]; afterwards it is only logged. Must run on an IO thread.
   */
  suspend fun start(
    context: Context,
    mode: String,
    arguments: List<String>,
    stdin: String,
    onFailure: (HostFailure) -> Unit,
  ): StartedHost {
    val connection = open(context)
    val request = JSONObject().put("type", "launch").put("args", JSONArray(arguments)).put("stdin", stdin)
    try {
      connection.send(request)
    } catch (error: IOException) {
      connection.close()
      throw HostFailure.Disconnected(error)
    }
    scope.launch { watch(connection, mode, onFailure) }
    // Once connected, the helper's connection stays open only to log the host's exit.
    return StartedHost { connected -> if (!connected) connection.close() }
  }

  /** Every helper port once, in order; the first helper that proves it holds one of [keys] wins. */
  fun reach(keys: List<ByteArray>, ports: List<Int> = HelperProtocol.PORTS): Reach {
    var rejected = false
    var foreign = 0
    for (port in ports) {
      when (val result = knock(port, keys)) {
        is HelperProtocol.Handshake.Authenticated -> return Reach.Connected(result.key, result.connection)
        HelperProtocol.Handshake.KeyMismatch -> rejected = true
        HelperProtocol.Handshake.Foreign -> foreign++
        null -> Unit
      }
    }
    return when {
      rejected -> Reach.Rejected
      foreign == ports.size -> Reach.PortsTaken
      else -> Reach.Unreachable
    }
  }

  /** Whether Pipkin's helper answers on any port, paired with Pipkin or not. */
  fun listening(context: Context): Boolean = when (val reach = reach(HelperPairing.keys(context).all)) {
    is Reach.Connected -> {
      reach.connection.close()
      true
    }

    Reach.Rejected -> true

    Reach.PortsTaken, Reach.Unreachable -> false
  }

  /** Asks the helper to bring Pipkin to the front; Termux may open activities while it is on screen itself. */
  fun requestReturn(context: Context) {
    val reach = reach(HelperPairing.keys(context).all) as? Reach.Connected ?: return
    reach.connection.use { connection ->
      try {
        connection.send(JSONObject().put("type", "return").put("package", context.packageName))
        val reply = connection.receive(REPLY_TIMEOUT_MILLIS)
        Log.i(TAG, "Helper return: ${reply?.optBoolean("ok")}")
      } catch (error: IOException) {
        Log.w(TAG, "Helper return failed: ${error.javaClass.simpleName}")
      }
    }
  }

  /** An authenticated helper connection running this APK's bundle, or the [HostFailure] that explains why not. */
  private suspend fun open(context: Context): HelperConnection {
    val keys = HelperPairing.keys(context).all
    if (keys.isEmpty()) throw HostFailure.HelperNotPaired()
    val connection = connect(context, keys, graceMillis = 0)
    val version = try {
      hello(connection)
    } catch (error: IOException) {
      connection.close()
      throw HostFailure.Disconnected(error)
    }
    if (version == HelperAssets.helperSha256(context) || updateOffered) return connection
    updateLock.withLock {
      // A launch that waited here holds a connection to the helper the first one replaced.
      if (!updateOffered) offerUpdate(context, connection)
      updateOffered = true
    }
    connection.close()
    return connect(context, keys, graceMillis = RESTART_TIMEOUT_MILLIS)
  }

  /**
   * Reaches the helper, waiting up to [graceMillis] for one that is restarting, then wakes Termux once if nothing
   * answers. A helper authenticating with the pending key confirms the pairing and completes its setup command.
   */
  private suspend fun connect(context: Context, keys: List<ByteArray>, graceMillis: Long): HelperConnection {
    val deadline = System.currentTimeMillis() + graceMillis
    var reach = reach(keys)
    while (reach == Reach.Unreachable && System.currentTimeMillis() < deadline) {
      delay(RESTART_POLL_MILLIS)
      reach = reach(keys)
    }
    if (reach == Reach.Unreachable && TermuxWaker.wake(context)) reach = reach(keys)
    val connected = when (reach) {
      is Reach.Connected -> reach
      Reach.Rejected -> throw HostFailure.HelperRejected()
      Reach.PortsTaken -> throw HostFailure.HelperPortTaken()
      Reach.Unreachable -> throw HostFailure.HelperUnreachable()
    }
    HelperPairing.confirm(context, connected.key)
    PlaySetup.complete(connected.key)
    return connected.connection
  }

  /** The bundle hash the helper reports. */
  private fun hello(connection: HelperConnection): String {
    connection.send(JSONObject().put("type", "hello"))
    val reply = connection.receive(REPLY_TIMEOUT_MILLIS) ?: throw IOException("Helper closed before its hello")
    if (reply.optString("type") != "hello") throw IOException("Unexpected helper reply")
    return reply.optString("version")
  }

  /** The helper replies, then exits for its wrapper to restart it with the new bundle. */
  private fun offerUpdate(context: Context, connection: HelperConnection) {
    try {
      val source = HelperAssets.read(context, HelperAssets.HELPER).toString(Charsets.UTF_8)
      val request = JSONObject().put("type", "update")
        .put("source", source)
        .put("sha256", HelperAssets.helperSha256(context))
      connection.send(request)
      val reply = connection.receive(REPLY_TIMEOUT_MILLIS)
      Log.i(TAG, "Helper update: ${reply?.optString("type")}")
    } catch (error: IOException) {
      Log.w(TAG, "Helper update failed: ${error.javaClass.simpleName}")
    }
  }

  /**
   * Reads the helper's reports for one launch until the child exits or the connection ends. Spawn errors and exits
   * fail the launch through [onFailure], which does nothing once the host has connected.
   */
  private fun watch(connection: HelperConnection, mode: String, onFailure: (HostFailure) -> Unit) = connection.use {
    while (true) {
      val message = try {
        connection.receive()
      } catch (error: IOException) {
        Log.w(TAG, "Helper connection for host $mode ended: ${error.javaClass.simpleName}")
        null
      } ?: return@use
      when (message.optString("type")) {
        "error" -> {
          // Not the helper's message: a spawn error can quote the command.
          Log.w(TAG, "Helper couldn't start host $mode")
          onFailure(HostFailure.TermuxError())
          return@use
        }

        "exit" -> {
          val exitCode = exitCode(message)
          val stderr = message.optString("stderr").takeLast(STDERR_LOG_LIMIT).trimEnd()
          Log.i(TAG, "Termux host $mode exited: exitCode=$exitCode stderr:\n$stderr")
          onFailure(HostResultReceiver.exitFailure(exitCode))
          return@use
        }
      }
    }
  }

  private fun exitCode(exit: JSONObject): Int {
    if (!exit.isNull("code")) return exit.optInt("code", -1)
    val signal = SIGNALS[exit.optString("signal")] ?: return -1
    return 128 + signal
  }

  /** The handshake's outcome on [port], or null when nothing listens there. */
  private fun knock(port: Int, keys: List<ByteArray>): HelperProtocol.Handshake? {
    val socket = Socket()
    try {
      socket.connect(InetSocketAddress(loopback, port), CONNECT_TIMEOUT_MILLIS)
    } catch (_: IOException) {
      socket.close()
      return null
    }
    val result = try {
      HelperProtocol.handshake(socket, keys)
    } catch (_: IOException) {
      HelperProtocol.Handshake.Foreign
    }
    if (result !is HelperProtocol.Handshake.Authenticated) socket.close()
    return result
  }
}
