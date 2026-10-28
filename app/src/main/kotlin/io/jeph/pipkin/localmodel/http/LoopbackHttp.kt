package io.jeph.pipkin.localmodel.http

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A minimal HTTP/1.1 server on 127.0.0.1 for the local model endpoints: Pi's requests to the main process, and the main
 * process's to the LiteRT-LM engine. One thread per connection, since a request lives as long as the model writes, and
 * one request per connection (`Connection: close`), which is all pi-ai's and the proxy's clients need.
 */
internal class LoopbackHttp(port: Int, private val handler: (HttpRequest, HttpResponse) -> Unit) : Closeable {
  private val server = ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
  private val connections: ExecutorService = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "local-model-http").apply { isDaemon = true }
  }

  val port: Int get() = server.localPort

  init {
    Thread({ acceptLoop() }, "local-model-http-accept").apply { isDaemon = true }.start()
  }

  override fun close() {
    server.close()
    connections.shutdownNow()
  }

  private fun acceptLoop() {
    while (!server.isClosed) {
      val socket = try {
        server.accept()
      } catch (_: IOException) {
        return
      }
      connections.execute { serve(socket) }
    }
  }

  private fun serve(socket: Socket) {
    socket.use {
      val input = BufferedInputStream(socket.getInputStream())
      val response = HttpResponse(socket.getOutputStream())
      val request = try {
        socket.soTimeout = READ_TIMEOUT_MILLIS
        readRequest(input)
      } catch (failure: HttpException) {
        response.error(failure.status, failure.message.orEmpty())
        return
      } catch (_: IOException) {
        return
      }
      socket.soTimeout = 0
      watchForHangUp(input, request)
      try {
        handler(request, response)
      } catch (_: IOException) {
        // The client went away mid-response; there is no one left to tell.
      }
    }
  }

  /**
   * A client sends nothing after its request, so the next read ends only when it closes the connection. That tells a
   * handler the client is gone while it has nothing to write, such as while a model reads a long prompt.
   */
  private fun watchForHangUp(input: InputStream, request: HttpRequest) {
    connections.execute {
      try {
        while (input.read() >= 0) Unit
      } catch (_: IOException) {
        // A reset is a hang-up too.
      }
      request.hangUp()
    }
  }

  private fun readRequest(input: InputStream): HttpRequest {
    val requestLine = readLine(input) ?: throw IOException("Connection closed before the request")
    val parts = requestLine.split(' ')
    if (parts.size != REQUEST_LINE_PARTS) throw HttpException(STATUS_BAD_REQUEST, "Malformed request line")
    val headers = buildMap {
      while (true) {
        val line = readLine(input) ?: throw IOException("Connection closed in the headers")
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon <= 0) throw HttpException(STATUS_BAD_REQUEST, "Malformed header")
        put(line.substring(0, colon).trim().lowercase(Locale.ROOT), line.substring(colon + 1).trim())
      }
    }
    val body = when {
      headers["transfer-encoding"]?.lowercase(Locale.ROOT) == "chunked" -> readChunked(input)
      else -> readFixed(input, headers["content-length"]?.toLongOrNull() ?: 0L)
    }
    return HttpRequest(parts[0], parts[1].substringBefore('?'), headers, body)
  }

  private fun readFixed(input: InputStream, length: Long): ByteArray {
    if (length > MAX_BODY_BYTES) throw HttpException(STATUS_TOO_LARGE, "Request body too large")
    val body = ByteArray(length.toInt())
    var read = 0
    while (read < body.size) {
      val count = input.read(body, read, body.size - read)
      if (count < 0) throw IOException("Connection closed in the body")
      read += count
    }
    return body
  }

  private fun readChunked(input: InputStream): ByteArray {
    val body = ByteArrayOutputStream()
    while (true) {
      val size = readLine(input)?.substringBefore(';')?.trim()?.toLongOrNull(HEX)
        ?: throw HttpException(STATUS_BAD_REQUEST, "Malformed chunk")
      if (size == 0L) {
        while (readLine(input)?.isNotEmpty() == true) Unit
        return body.toByteArray()
      }
      if (body.size() + size > MAX_BODY_BYTES) throw HttpException(STATUS_TOO_LARGE, "Request body too large")
      body.write(readFixed(input, size))
      readLine(input)
    }
  }

  /** One CRLF- or LF-terminated header line, or null at the end of the stream. */
  private fun readLine(input: InputStream): String? {
    val line = ByteArrayOutputStream()
    while (true) {
      val byte = input.read()
      if (byte < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
      if (byte == '\n'.code) break
      if (line.size() >= MAX_LINE_BYTES) throw HttpException(STATUS_TOO_LARGE, "Header line too long")
      line.write(byte)
    }
    return line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
  }

  private class HttpException(val status: Int, message: String) : IOException(message)

  private companion object {
    const val BACKLOG = 16
    const val READ_TIMEOUT_MILLIS = 30_000
    const val MAX_LINE_BYTES = 16 * 1024
    const val MAX_BODY_BYTES = 64L * 1024 * 1024
    const val REQUEST_LINE_PARTS = 3
    const val HEX = 16
    const val STATUS_BAD_REQUEST = 400
    const val STATUS_TOO_LARGE = 413
  }
}

/** A request with its headers' names lowercased. */
internal class HttpRequest(
  val method: String,
  val path: String,
  val headers: Map<String, String>,
  val body: ByteArray,
) {
  private val lock = Any()
  private var gone = false
  private val hangUpActions = mutableListOf<() -> Unit>()

  /** The token of an `Authorization: Bearer <token>` header. */
  val bearer: String? get() = headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")

  /** Runs [action] once the client closes its connection, at once if it already has. */
  fun onHangUp(action: () -> Unit) {
    val now = synchronized(lock) { gone.also { if (!it) hangUpActions += action } }
    if (now) action()
  }

  internal fun hangUp() {
    val actions = synchronized(lock) {
      gone = true
      hangUpActions.toList().also { hangUpActions.clear() }
    }
    actions.forEach { it() }
  }
}

/**
 * The response to one [HttpRequest]: either [respond] with a whole body, or [stream] and then [write] until the handler
 * returns. A write that fails throws [IOException], which is how a handler learns that the client went away.
 */
internal class HttpResponse(private val output: OutputStream) {
  private var started = false

  val isStarted: Boolean get() = started

  fun respond(status: Int, contentType: String, body: ByteArray) {
    check(!started) { "Response already started" }
    started = true
    output.write(head(status, contentType, body.size.toLong()).toByteArray(Charsets.ISO_8859_1))
    output.write(body)
    output.flush()
  }

  fun respondJson(status: Int, json: String) = respond(status, JSON, json.toByteArray())

  /** An OpenAI-style error body. */
  fun error(status: Int, message: String, type: String = "server_error") {
    if (started) return
    val body = org.json.JSONObject()
      .put("error", org.json.JSONObject().put("message", message).put("type", type))
    respondJson(status, body.toString())
  }

  /** Starts a body of unknown length, ended by closing the connection. */
  fun stream(status: Int, contentType: String) {
    check(!started) { "Response already started" }
    started = true
    output.write(head(status, contentType, null).toByteArray(Charsets.ISO_8859_1))
    output.flush()
  }

  fun write(bytes: ByteArray, length: Int = bytes.size) {
    output.write(bytes, 0, length)
    output.flush()
  }

  fun write(text: String) = write(text.toByteArray())

  private fun head(status: Int, contentType: String, length: Long?): String = buildString {
    append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
    append("Content-Type: ").append(contentType).append("\r\n")
    if (length != null) append("Content-Length: ").append(length).append("\r\n")
    if (contentType == EVENT_STREAM) append("Cache-Control: no-cache\r\n")
    append("Connection: close\r\n\r\n")
  }

  private fun reason(status: Int): String = when (status) {
    200 -> "OK"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    404 -> "Not Found"
    413 -> "Payload Too Large"
    503 -> "Service Unavailable"
    else -> if (status >= 500) "Internal Server Error" else "Status"
  }

  companion object {
    const val JSON = "application/json"
    const val EVENT_STREAM = "text/event-stream"
  }
}
