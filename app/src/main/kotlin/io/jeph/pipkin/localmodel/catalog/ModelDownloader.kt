package io.jeph.pipkin.localmodel.catalog

import io.jeph.pipkin.localmodel.ModelArtifact
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Why a model download stopped; the UI words each one. */
internal sealed interface DownloadFailure {
  /** The phone has less free storage than the [neededBytes] still to download. */
  data class NotEnoughSpace(val neededBytes: Long) : DownloadFailure

  /** The connection failed or dropped; the bytes so far are kept, so trying again resumes. */
  data object Network : DownloadFailure

  /** The server answered with HTTP [status]. */
  data class Http(val status: Int) : DownloadFailure

  /** The whole file arrived but isn't the file the catalog pins; it was deleted. */
  data object Corrupt : DownloadFailure
}

internal class DownloadException(val failure: DownloadFailure, cause: Throwable? = null) :
  Exception(failure.toString(), cause)

/** A response to a download request, from [offset] onwards when the server honoured the range. */
internal class DownloadResponse(val status: Int, val body: InputStream) : Closeable {
  override fun close() = body.close()
}

/** Where a model's bytes come from; the tests serve them from memory. */
internal fun interface DownloadSource {
  /** Requests [url] from byte [offset] on; throws [IOException] when there's no answer. */
  fun open(url: String, offset: Long): DownloadResponse
}

/** Downloads over HTTPS, asking for the rest of a file with a `Range` header. */
internal object HttpsDownloadSource : DownloadSource {
  override fun open(url: String, offset: Long): DownloadResponse {
    val parsed = URL(url)
    require(parsed.protocol == "https") { "Model downloads must use HTTPS" }
    // Hugging Face redirects to its CDN over HTTPS, which HttpURLConnection follows, keeping the Range header.
    val connection = parsed.openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MILLIS
    connection.readTimeout = TIMEOUT_MILLIS
    if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
    val status = connection.responseCode
    val body = if (status in HTTP_SUCCESS) connection.inputStream else connection.errorStream
    return DownloadResponse(status, body ?: ByteArrayInputStream(ByteArray(0)))
  }

  private const val TIMEOUT_MILLIS = 30_000
  private val HTTP_SUCCESS = 200..299
}

/**
 * Downloads catalog files into [dir]. Bytes land in `<file>.part`, which survives a dropped connection or a killed
 * process, so the next attempt asks for the rest. The SHA-256 is computed as the bytes arrive (a resumed download
 * first reads back what it kept), and only a file that matches the catalog is renamed to its final name, so a file
 * under its own name is always a verified one.
 */
internal class ModelDownloader(
  private val dir: File,
  private val source: DownloadSource,
  private val freeBytes: (File) -> Long = File::getUsableSpace,
) {
  fun file(artifact: ModelArtifact) = File(dir, artifact.fileName)

  fun partFile(artifact: ModelArtifact) = File(dir, artifact.fileName + PART_SUFFIX)

  /**
   * Downloads [artifact] and returns its verified file, reporting the bytes on disk through [onProgress]. Blocks;
   * cancelling the calling coroutine stops it between chunks and keeps the part. Throws [DownloadException].
   */
  suspend fun download(artifact: ModelArtifact, onProgress: (Long) -> Unit): File {
    dir.mkdirs()
    val part = partFile(artifact)
    if (part.length() > artifact.sizeBytes) part.delete()
    val needed = artifact.sizeBytes - part.length()
    if (freeBytes(dir) < needed + SPACE_MARGIN_BYTES) throw DownloadException(DownloadFailure.NotEnoughSpace(needed))
    val digest = MessageDigest.getInstance("SHA-256")
    var offset = readBack(part, digest)
    onProgress(offset)
    if (offset < artifact.sizeBytes) {
      val response = try {
        source.open(artifact.url, offset)
      } catch (e: IOException) {
        throw DownloadException(DownloadFailure.Network, e)
      }
      response.use {
        when (it.status) {
          HTTP_PARTIAL -> Unit

          // The server sent the whole file, so the part is thrown away rather than appended to.
          HTTP_OK -> if (offset > 0) {
            offset = 0
            digest.reset()
          }

          else -> throw DownloadException(DownloadFailure.Http(it.status))
        }
        offset = receive(it.body, part, offset, digest, onProgress)
      }
    }
    if (offset != artifact.sizeBytes) throw DownloadException(DownloadFailure.Network)
    if (digest.digest().toHex() != artifact.sha256.lowercase()) {
      part.delete()
      throw DownloadException(DownloadFailure.Corrupt)
    }
    val file = file(artifact)
    if (!part.renameTo(file)) throw DownloadException(DownloadFailure.Network, IOException("Can't rename $part"))
    return file
  }

  /** Feeds what [part] already holds into [digest] and returns its length. */
  private suspend fun readBack(part: File, digest: MessageDigest): Long {
    if (!part.exists()) return 0
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    FileInputStream(part).use { input ->
      while (true) {
        coroutineContext.ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        total += read
      }
    }
    return total
  }

  /** Writes [body] to [part] from [offset], hashing it, and returns the part's new length. */
  private suspend fun receive(
    body: InputStream,
    part: File,
    offset: Long,
    digest: MessageDigest,
    onProgress: (Long) -> Unit,
  ): Long {
    val buffer = ByteArray(BUFFER_BYTES)
    var written = offset
    var reported = offset
    try {
      FileOutputStream(part, offset > 0).use { output ->
        while (true) {
          coroutineContext.ensureActive()
          val read = body.read(buffer)
          if (read < 0) break
          output.write(buffer, 0, read)
          digest.update(buffer, 0, read)
          written += read
          if (written - reported >= PROGRESS_STEP_BYTES) {
            reported = written
            onProgress(written)
          }
        }
      }
    } catch (e: IOException) {
      throw DownloadException(DownloadFailure.Network, e)
    }
    onProgress(written)
    return written
  }

  companion object {
    const val PART_SUFFIX = ".part"
    private const val HTTP_OK = 200
    private const val HTTP_PARTIAL = 206
    private const val BUFFER_BYTES = 256 * 1024
    private const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024

    /** Left free beyond the file, so a download never fills the phone to the last byte. */
    private const val SPACE_MARGIN_BYTES = 256L * 1024 * 1024
  }
}

private fun ByteArray.toHex(): String = buildString(size * 2) {
  for (byte in this@toHex) {
    val value = byte.toInt() and 0xff
    append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0xf])
  }
}

private const val HEX_DIGITS = "0123456789abcdef"
