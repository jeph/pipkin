package io.jeph.pipkin.termux

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import org.json.JSONObject

internal const val PI_VERSION = "0.85.1"
internal const val WIRE_LIMIT = 16 * 1024 * 1024
internal const val RPC_SUBPROTOCOL = "pipkin.rpc.v1"
internal const val RPC_EVENT_METHOD = "agent.event"

internal enum class RuntimeFailureKind {
  MissingTermux,
  Permission,
  ExternalApps,
  Prerequisites,
  Setup,
  Authentication,
  Compatibility,
  Endpoint,
  Transport,
  Storage,
}

internal class RuntimeFailure(val kind: RuntimeFailureKind, message: String, cause: Throwable? = null) :
  IOException(message, cause)

/** The daemon refused a request; `reason` is its bounded, user-presentable text. */
internal class RuntimeRejection(val reason: String) : IOException(reason)

/** Only an explicit -32000 rejection permits the coordinator to journal a rejected receipt. */
internal fun rpcResult(record: JSONObject): JSONObject {
  if (record.has("result") == record.has("error") || record.has("params")) protocolFailure()
  if (record.has("result")) return record.optJSONObject("result") ?: protocolFailure()
  val error = record.optJSONObject("error") ?: protocolFailure()
  val code = error.integer("code")
  val message = error.string("message").take(4096)
  when (code) {
    -32000L -> throw RuntimeRejection(message)
    -32001L -> throw RuntimeFailure(RuntimeFailureKind.Transport, message)
    else -> protocolFailure()
  }
}
internal fun protocolFailure(): Nothing = throw RuntimeFailure(
  RuntimeFailureKind.Compatibility,
  "The Termux supervisor returned an incompatible or malformed record. Stop it before retrying.",
)

internal fun JSONObject.string(name: String): String = opt(name) as? String ?: protocolFailure()

internal fun JSONObject.integer(name: String): Long = when (val value = opt(name)) {
  is Int -> value.toLong()
  is Long -> value
  else -> protocolFailure()
}

internal fun JSONObject.boolean(name: String): Boolean = opt(name) as? Boolean ?: protocolFailure()

internal fun validUuid(value: String): Boolean = try {
  UUID.fromString(value).toString() == value
} catch (_: IllegalArgumentException) {
  false
}

internal fun validDigest(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun ByteArray.hex(): String = toHexString()

/** Android's JSONTokener accepts duplicate keys, comments and non-JSON numbers. Validate first. */
internal fun strictObject(bytes: ByteArray): JSONObject {
  val text = try {
    Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString()
  } catch (_: java.nio.charset.CharacterCodingException) {
    protocolFailure()
  }
  StrictJson(text).validate()
  return try {
    JSONObject(text)
  } catch (_: org.json.JSONException) {
    protocolFailure()
  }
}

private class StrictJson(private val text: String) {
  private var position = 0

  fun validate() {
    whitespace()
    if (text.getOrNull(position) != '{') protocolFailure()
    value(0)
    whitespace()
    if (position != text.length) protocolFailure()
  }

  private fun value(depth: Int) {
    if (depth > 64) protocolFailure()
    whitespace()
    when (text.getOrNull(position)) {
      '{' -> objectValue(depth + 1)
      '[' -> arrayValue(depth + 1)
      '"' -> stringValue()
      't' -> literal("true")
      'f' -> literal("false")
      'n' -> literal("null")
      '-', in '0'..'9' -> number()
      else -> protocolFailure()
    }
  }

  private fun objectValue(depth: Int) {
    position++
    whitespace()
    if (take('}')) return
    val keys = HashSet<String>()
    do {
      whitespace()
      if (text.getOrNull(position) != '"' || !keys.add(stringValue())) protocolFailure()
      whitespace()
      if (!take(':')) protocolFailure()
      value(depth)
      whitespace()
      if (take('}')) return
    } while (take(','))
    protocolFailure()
  }

  private fun arrayValue(depth: Int) {
    position++
    whitespace()
    if (take(']')) return
    do {
      value(depth)
      whitespace()
      if (take(']')) return
    } while (take(','))
    protocolFailure()
  }

  private fun stringValue(): String {
    if (!take('"')) protocolFailure()
    val result = StringBuilder()
    while (position < text.length) {
      val character = text[position++]
      if (character == '"') return result.toString()
      if (character < ' ') protocolFailure()
      if (character != '\\') {
        result.append(character)
        continue
      }
      when (val escaped = text.getOrNull(position++)) {
        '"', '\\', '/' -> result.append(escaped)

        'b' -> result.append('\b')

        'f' -> result.append('\u000c')

        'n' -> result.append('\n')

        'r' -> result.append('\r')

        't' -> result.append('\t')

        'u' -> {
          if (position + 4 > text.length) protocolFailure()
          val digits = text.substring(position, position + 4)
          if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) protocolFailure()
          result.append(digits.toInt(16).toChar())
          position += 4
        }

        else -> protocolFailure()
      }
    }
    protocolFailure()
  }

  private fun number() {
    val start = position
    take('-')
    if (!take('0')) {
      if (text.getOrNull(position) !in '1'..'9') protocolFailure()
      digits()
    }
    if (take('.')) {
      if (text.getOrNull(position) !in '0'..'9') protocolFailure()
      digits()
    }
    if (take('e') || take('E')) {
      if (!take('+')) take('-')
      if (text.getOrNull(position) !in '0'..'9') protocolFailure()
      digits()
    }
    if (text.substring(start, position).toDoubleOrNull()?.isFinite() != true) protocolFailure()
  }

  private fun digits() {
    while (text.getOrNull(position) in '0'..'9') position++
  }

  private fun literal(literal: String) {
    if (!text.startsWith(literal, position)) protocolFailure()
    position += literal.length
  }

  private fun whitespace() {
    while (text.getOrNull(position) in listOf(' ', '\n', '\r', '\t')) position++
  }

  private fun take(character: Char): Boolean = if (text.getOrNull(position) == character) {
    position++
    true
  } else {
    false
  }
}
