package io.jeph.pipkin.host

import java.io.IOException
import java.io.InputStream

/**
 * JSONL framing with LF as the only delimiter, split on bytes so U+2028/U+2029 inside JSON
 * strings survive (`BufferedReader.readLine` would also split on a lone CR). A trailing CR is
 * stripped. Not thread-safe: one reader per stream.
 */
internal class LineReader(private val input: InputStream) {
  private val chunk = ByteArray(CHUNK_BYTES)
  private var chunkStart = 0
  private var chunkEnd = 0
  private var line = ByteArray(INITIAL_LINE_BYTES)
  private var lineLength = 0

  /** Next line without its terminator, or null at end of stream; a partial last line is dropped. */
  fun readLine(maxBytes: Int): String? {
    lineLength = 0
    while (true) {
      if (chunkStart == chunkEnd) {
        val read = input.read(chunk)
        if (read < 0) return null
        chunkStart = 0
        chunkEnd = read
      }
      var index = chunkStart
      while (index < chunkEnd && chunk[index] != LF) index++
      append(index - chunkStart, maxBytes)
      if (index < chunkEnd) {
        chunkStart = index + 1
        return take()
      }
      chunkStart = chunkEnd
    }
  }

  private fun append(count: Int, maxBytes: Int) {
    if (lineLength + count > maxBytes) throw IOException("Line exceeds $maxBytes bytes")
    if (lineLength + count > line.size) {
      line = line.copyOf(maxOf(line.size * 2, lineLength + count).coerceAtMost(maxBytes))
    }
    System.arraycopy(chunk, chunkStart, line, lineLength, count)
    lineLength += count
  }

  private fun take(): String {
    val length = if (lineLength > 0 && line[lineLength - 1] == CR) lineLength - 1 else lineLength
    val text = String(line, 0, length, Charsets.UTF_8)
    // One huge line (an image) must not pin a 16 MiB buffer for the channel's lifetime.
    if (line.size > RETAINED_LINE_BYTES) line = ByteArray(INITIAL_LINE_BYTES)
    return text
  }

  private companion object {
    const val LF: Byte = 0x0A
    const val CR: Byte = 0x0D
    const val CHUNK_BYTES = 64 * 1024
    const val INITIAL_LINE_BYTES = 8 * 1024
    const val RETAINED_LINE_BYTES = 256 * 1024
  }
}
