package io.jeph.pipkin.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The object a streamed JSON text holds so far, read the way Pi's `parseStreamingJson` reads it: an
 * unfinished string keeps what has arrived, while an unfinished key, number or literal is left out, as
 * is a key still waiting for its value. Null when the text does not open an object. Pi's RPC mode
 * drops that parsed form from `toolcall_delta`, so the app rebuilds it from the raw deltas.
 */
internal fun parsePartialJsonObject(text: String): JSONObject? = readPartialJsonObject(text)?.value

/**
 * [value] as [parsePartialJsonObject] reads it; [lastKey] is the top-level key read last, whose value is the
 * only one that may still grow.
 */
internal class PartialJsonObject(val value: JSONObject, val lastKey: String?)

internal fun readPartialJsonObject(text: String): PartialJsonObject? {
  val reader = PartialJsonReader(text)
  val value = reader.readTopObject() ?: return null
  return PartialJsonObject(value, reader.lastTopKey)
}

private class PartialJsonReader(private val text: String) {
  private var index = 0

  /** The top-level object's key whose value was read last. */
  var lastTopKey: String? = null
    private set

  fun readTopObject(): JSONObject? {
    skipWhitespace()
    return if (peek() == '{') readObject(top = true) else null
  }

  /** The value at the cursor, or null when none has fully started or a number or literal may still grow. */
  private fun readValue(): Any? {
    skipWhitespace()
    return when (peek()) {
      null -> null
      '{' -> readObject(top = false)
      '[' -> readArray()
      '"' -> readString().text
      else -> readScalar()
    }
  }

  private fun readObject(top: Boolean): JSONObject {
    val result = JSONObject()
    index++
    while (true) {
      skipWhitespace()
      when (peek()) {
        '}' -> {
          index++
          return result
        }

        ',' -> {
          index++
          continue
        }

        '"' -> Unit

        else -> return result
      }
      val key = readString()
      skipWhitespace()
      if (!key.closed || peek() != ':') return result
      index++
      if (top) lastTopKey = key.text
      val value = readValue() ?: return result
      result.put(key.text, value)
    }
  }

  private fun readArray(): JSONArray {
    val result = JSONArray()
    index++
    while (true) {
      skipWhitespace()
      when (peek()) {
        ']' -> {
          index++
          return result
        }

        ',' -> {
          index++
          continue
        }
      }
      result.put(readValue() ?: return result)
    }
  }

  /** The string at the opening quote under the cursor; an escape cut off by the end of the text is left out. */
  private fun readString(): JsonString {
    index++
    val out = StringBuilder()
    while (index < text.length) {
      when (val char = text[index]) {
        '"' -> {
          index++
          return JsonString(out.toString(), closed = true)
        }

        '\\' -> readEscape(out)

        else -> {
          out.append(char)
          index++
        }
      }
    }
    return JsonString(out.toString(), closed = false)
  }

  /** Appends the escape at the cursor to [out]; one cut off by the end of the text moves the cursor there. */
  private fun readEscape(out: StringBuilder) {
    val escape = text.getOrNull(index + 1)
    val unicodeEnd = index + UNICODE_ESCAPE_LENGTH
    val code = if (unicodeEnd <= text.length) text.substring(index + 2, unicodeEnd).toIntOrNull(HEX) else null
    when {
      escape == null -> index = text.length

      escape != 'u' -> {
        out.append(ESCAPES[escape] ?: escape)
        index += 2
      }

      // Cut off, or not hex at all: nothing after it reads as part of the string either.
      code == null -> index = text.length

      else -> {
        out.append(code.toChar())
        index = unicodeEnd
      }
    }
  }

  /** A number, `true`, `false` or `null`; null while it runs to the end of the text, since it may still grow. */
  private fun readScalar(): Any? {
    val start = index
    while (index < text.length && text[index] !in SCALAR_END) index++
    if (index == text.length) return null
    // JSONObject rejects NaN and infinities, which a malformed number could parse to.
    return when (val token = text.substring(start, index)) {
      "true" -> true
      "false" -> false
      "null" -> JSONObject.NULL
      else -> token.toLongOrNull() ?: token.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
  }

  private fun peek(): Char? = text.getOrNull(index)

  private fun skipWhitespace() {
    while (index < text.length && text[index] in WHITESPACE) index++
  }

  private class JsonString(val text: String, val closed: Boolean)

  private companion object {
    const val HEX = 16

    /** `\uXXXX`: the backslash, the `u` and four hex digits. */
    const val UNICODE_ESCAPE_LENGTH = 6
    const val WHITESPACE = " \t\r\n"
    const val SCALAR_END = ",}] \t\r\n"
    val ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b', 'f' to '\u000C')
  }
}
