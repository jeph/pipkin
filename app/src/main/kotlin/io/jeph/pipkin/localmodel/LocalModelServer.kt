package io.jeph.pipkin.localmodel

import io.jeph.pipkin.localmodel.http.HttpRequest
import io.jeph.pipkin.localmodel.http.HttpResponse
import io.jeph.pipkin.localmodel.http.LoopbackHttp
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The endpoint Pi's `pipkin-local` provider talks to: OpenAI's chat completions, keyed with [apiKey], on loopback
 * [port]. Each request waits its turn in the scheduler through [serve], then is proxied byte for byte to the model the
 * `:inference` process serves, while [StreamProgress] reads the chunks for the chat's status line.
 *
 * Streamed requests get their response headers at once and an SSE comment every [KEEPALIVE_MILLIS] while they wait,
 * so pi-ai's idle timeout doesn't fire while another chat holds the model or a model loads. A client that goes away
 * while waiting fails that write, which takes its request out of the queue; one that goes away while the model
 * writes closes the upstream connection, which stops generation.
 */
internal class LocalModelServer(
  port: Int,
  private val apiKey: () -> String,
  private val installed: () -> List<InstalledModel>,
  private val serve: suspend (session: String?, modelId: String, block: suspend (ModelEndpoint) -> Unit) -> Unit,
  private val report: (session: String?, activity: SessionActivity?) -> Unit,
) : AutoCloseable {
  private val http = LoopbackHttp(port, ::handle)

  override fun close() = http.close()

  private fun handle(request: HttpRequest, response: HttpResponse) {
    if (!authorized(request)) {
      response.error(STATUS_UNAUTHORIZED, "Invalid API key", "authentication_error")
      return
    }
    when {
      request.method == "GET" && request.path == "/v1/models" -> listModels(response)
      request.method == "POST" && request.path == "/v1/chat/completions" -> complete(request, response)
      else -> response.error(STATUS_NOT_FOUND, "No route for ${request.method} ${request.path}", "not_found")
    }
  }

  private fun authorized(request: HttpRequest): Boolean {
    val token = request.bearer ?: return false
    return MessageDigest.isEqual(token.toByteArray(), apiKey().toByteArray())
  }

  private fun listModels(response: HttpResponse) {
    val data = JSONArray(installed().map { JSONObject().put("id", it.model.id).put("object", "model") })
    response.respondJson(STATUS_OK, JSONObject().put("object", "list").put("data", data).toString())
  }

  private fun complete(request: HttpRequest, response: HttpResponse) {
    val body = try {
      JSONObject(String(request.body))
    } catch (_: JSONException) {
      response.error(STATUS_BAD_REQUEST, "The body isn't JSON", "invalid_request_error")
      return
    }
    val modelId = body.optString("model")
    if (installed().none { it.model.id == modelId }) {
      response.error(STATUS_NOT_FOUND, "Model $modelId isn't downloaded", "model_not_found")
      return
    }
    val session = request.headers[SESSION_HEADER]
    val stream = body.optBoolean("stream")
    val client = Client(response)
    if (stream) client.startStream()
    try {
      runBlocking(Dispatchers.IO) {
        val call = currentCoroutineContext().job
        request.onHangUp { call.cancel() }
        // Comments between events keep pi-ai's idle timeout from firing while the request waits or a prompt is read.
        val keepalive = if (stream) launch { client.keepAlive() } else null
        try {
          serve(session, modelId) { endpoint -> proxy(endpoint, body, stream, session, client) }
        } catch (failure: LocalModelException) {
          client.fail(failure.message)
        } catch (failure: IOException) {
          client.fail(failure.message.orEmpty())
        } finally {
          keepalive?.cancel()
          report(session, null)
        }
      }
    } catch (_: CancellationException) {
      // Pi hung up; the scheduler has dropped or ended its request.
    }
  }

  private suspend fun proxy(
    endpoint: ModelEndpoint,
    body: JSONObject,
    stream: Boolean,
    session: String?,
    client: Client,
  ) {
    val connection = URL("http://127.0.0.1:${endpoint.port}/v1/chat/completions").openConnection() as HttpURLConnection
    coroutineScope {
      // The reads below block, so cancelling the call can't reach them; closing the connection fails them, and the
      // engine sees the hang-up and stops the model.
      val aborter = launch {
        try {
          awaitCancellation()
        } finally {
          connection.disconnect()
        }
      }
      try {
        withContext(Dispatchers.IO) { exchange(connection, endpoint, body, stream, session, client) }
      } finally {
        aborter.cancel()
      }
    }
  }

  private fun exchange(
    connection: HttpURLConnection,
    endpoint: ModelEndpoint,
    body: JSONObject,
    stream: Boolean,
    session: String?,
    client: Client,
  ) {
    val bytes = JSONObject(body.toString())
      .put("model", endpoint.modelId)
      .put("return_progress", true)
      .toString()
      .toByteArray()
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.readTimeout = 0
    connection.setFixedLengthStreamingMode(bytes.size)
    connection.setRequestProperty("Content-Type", HttpResponse.JSON)
    connection.setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
    connection.outputStream.use { it.write(bytes) }
    val status = connection.responseCode
    when {
      status !in STATUS_OK until STATUS_MULTIPLE ->
        client.upstreamError(status, connection.errorStream?.use { String(it.readBytes()) }.orEmpty())

      stream -> relayStream(connection, session, client)

      else -> client.respond(status, connection.inputStream.use { it.readBytes() })
    }
  }

  private fun relayStream(connection: HttpURLConnection, session: String?, client: Client) {
    val progress = StreamProgress()
    connection.inputStream.bufferedReader().useLines { lines ->
      for (line in lines) {
        if (!line.startsWith(DATA)) {
          client.writeLine(line)
          continue
        }
        val payload = progress.onData(line.removePrefix(DATA).trimStart())
        report(session, progress.activity)
        client.writeLine(DATA + payload)
      }
    }
  }

  /** The response to Pi, written from the handler thread and the keepalive coroutine. */
  private class Client(private val response: HttpResponse) {
    private val lock = Any()

    fun startStream() = synchronized(lock) { response.stream(STATUS_OK, HttpResponse.EVENT_STREAM) }

    /** Writes an SSE comment now and then until cancelled or the write fails, which the hang-up watcher handles. */
    suspend fun keepAlive() {
      while (true) {
        delay(KEEPALIVE_MILLIS)
        try {
          synchronized(lock) { response.write(": waiting for the local model\n\n") }
        } catch (_: IOException) {
          return
        }
      }
    }

    fun writeLine(line: String) = synchronized(lock) { response.write(line + "\n") }

    fun respond(status: Int, body: ByteArray) = synchronized(lock) { response.respond(status, HttpResponse.JSON, body) }

    /** An error from the engine: its body as is before the stream started, as an SSE error event after. */
    fun upstreamError(status: Int, body: String) = synchronized(lock) {
      if (!response.isStarted) {
        response.respond(status, HttpResponse.JSON, body.toByteArray())
        return@synchronized
      }
      val error = try {
        JSONObject(body).optJSONObject("error")
      } catch (_: JSONException) {
        null
      } ?: JSONObject().put("message", body.ifEmpty { "HTTP $status" }).put("type", "server_error")
      writeError(error)
    }

    fun fail(message: String) = synchronized(lock) {
      if (!response.isStarted) {
        response.error(STATUS_UNAVAILABLE, message, ERROR_TYPE)
        return@synchronized
      }
      try {
        writeError(JSONObject().put("message", message).put("type", ERROR_TYPE))
      } catch (_: IOException) {
        // Pi already went away.
      }
    }

    private fun writeError(error: JSONObject) {
      response.write("$DATA${JSONObject().put("error", error)}\n\n")
    }
  }

  companion object {
    /** The header pi-ai sends the session id in when `compat.sendSessionAffinityHeaders` is on. */
    const val SESSION_HEADER = "x-session-affinity"
    private const val DATA = "data: "
    private const val ERROR_TYPE = "local_model_unavailable"
    private const val KEEPALIVE_MILLIS = 10_000L
    private const val STATUS_OK = 200
    private const val STATUS_MULTIPLE = 300
    private const val STATUS_BAD_REQUEST = 400
    private const val STATUS_UNAUTHORIZED = 401
    private const val STATUS_NOT_FOUND = 404
    private const val STATUS_UNAVAILABLE = 503
  }
}
