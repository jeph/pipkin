package io.jeph.pipkin.host

import android.util.Log
import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * One authenticated connection from a `host.mjs` process. A single reader coroutine starts at
 * construction and buffers every line until collected, so [closed] completes on EOF even when
 * nobody is collecting [lines].
 */
internal class HostChannel(
  private val socket: Socket,
  private val reader: LineReader,
  val hello: JSONObject,
  scope: CoroutineScope,
) {
  private val output = socket.getOutputStream()
  private val writeLock = Mutex()
  private val inbox = Channel<JSONObject>(Channel.UNLIMITED)
  private val done = CompletableDeferred<Unit>()

  /** Every line after the hello. Single collector: lines are consumed, not broadcast. */
  val lines: Flow<JSONObject> = inbox.receiveAsFlow()

  /** Completes once the connection has ended for any reason. */
  val closed: Deferred<Unit> get() = done

  init {
    scope.launch {
      var ending = "end of stream"
      try {
        while (true) {
          val line = reader.readLine(HostProtocol.MAX_LINE_BYTES) ?: break
          parse(line)?.let { inbox.send(it) }
        }
      } catch (error: IOException) {
        // Socket closed locally or by the host, or an oversized line; both end the channel.
        ending = "${error.javaClass.simpleName}: ${error.message}"
      } finally {
        inbox.close()
        close()
        done.complete(Unit)
        Log.i(TAG, "Host ${hello.optString("mode")} channel ended: $ending")
      }
    }
  }

  /** Writes one JSONL record; throws [IOException] once the connection is gone. */
  suspend fun send(obj: JSONObject) = write { obj.toString() }

  /** Writes [line], one JSON value without its newline, for a record whose key order matters to the host. */
  suspend fun sendLine(line: String) = write { line }

  private suspend fun write(encode: () -> String) {
    writeLock.withLock {
      // Encoded under the lock so messages keep their send order, and off the caller's thread, which may be the main
      // one: ensure's request carries over a megabyte of extension source.
      withContext(Dispatchers.IO) {
        output.write("${encode()}\n".toByteArray(Charsets.UTF_8))
        output.flush()
      }
    }
  }

  /** Idempotent. Unblocks the reader, which then completes [closed]. */
  fun close() {
    try {
      socket.close()
    } catch (_: IOException) {
      // Already closed or reset; nothing left to release.
    }
  }

  private fun parse(line: String): JSONObject? = try {
    JSONObject(line)
  } catch (_: JSONException) {
    // Never log the line itself: it can carry prompts, tokens or API keys.
    Log.w(TAG, "Skipping malformed host line (${line.length} chars)")
    null
  }

  private companion object {
    const val TAG = "PipkinHost"
  }
}
