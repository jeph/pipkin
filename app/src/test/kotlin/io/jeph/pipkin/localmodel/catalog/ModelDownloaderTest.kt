package io.jeph.pipkin.localmodel.catalog

import io.jeph.pipkin.localmodel.ModelArtifact
import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineKind
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloaderTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val content = Random(7).nextBytes(1_000_000)

  private fun artifact(bytes: ByteArray = content, sha256: String = sha256(bytes)) = ModelArtifact(
    engine = EngineKind.LITERT,
    backend = Backend.CPU,
    url = "https://example.com/model.litertlm",
    sha256 = sha256,
    sizeBytes = bytes.size.toLong(),
    fileName = "model.litertlm",
    contextSize = 4096,
  )

  /** Serves [content], from the offset asked for unless [ignoresRange]; records each offset asked for. */
  private class FakeSource(
    private val content: ByteArray,
    private val ignoresRange: Boolean = false,
    private val status: Int? = null,
    private val dropAfter: Int? = null,
  ) : DownloadSource {
    val offsets = mutableListOf<Long>()

    override fun open(url: String, offset: Long): DownloadResponse {
      offsets += offset
      status?.let { return DownloadResponse(it, ByteArrayInputStream(ByteArray(0))) }
      val from = if (ignoresRange) 0 else offset.toInt()
      val body: InputStream = ByteArrayInputStream(content, from, content.size - from)
      val served = dropAfter?.let { DroppingStream(body, it) } ?: body
      return DownloadResponse(if (ignoresRange || offset == 0L) 200 else 206, served)
    }
  }

  /** Fails like a dropped connection once [limit] bytes have been read. */
  private class DroppingStream(private val body: InputStream, private var limit: Int) : InputStream() {
    override fun read(): Int = throw UnsupportedOperationException()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (limit <= 0) throw IOException("connection reset")
      val read = body.read(b, off, minOf(len, limit))
      if (read > 0) limit -= read
      return read
    }
  }

  private fun downloader(source: DownloadSource, free: Long = Long.MAX_VALUE) =
    ModelDownloader(folder.root, source) { free }

  private fun failureOf(block: suspend () -> Unit): DownloadFailure {
    try {
      runBlocking { block() }
    } catch (e: DownloadException) {
      return e.failure
    }
    fail("Expected the download to fail")
    error("unreachable")
  }

  @Test
  fun aVerifiedDownloadEndsUnderItsOwnName() = runBlocking {
    val loader = downloader(FakeSource(content))
    val file = loader.download(artifact()) {}
    assertArrayEquals(content, file.readBytes())
    assertEquals("model.litertlm", file.name)
    assertFalse(loader.partFile(artifact()).exists())
  }

  @Test
  fun aHashMismatchDeletesTheFileAndFails() {
    val loader = downloader(FakeSource(content))
    val wrong = artifact(sha256 = "f".repeat(64))
    assertEquals(DownloadFailure.Corrupt, failureOf { loader.download(wrong) {} })
    assertFalse(loader.partFile(wrong).exists())
    assertFalse(loader.file(wrong).exists())
  }

  @Test
  fun aKeptPartResumesFromItsLength() = runBlocking {
    val source = FakeSource(content)
    val loader = downloader(source)
    val part = loader.partFile(artifact())
    part.writeBytes(content.copyOf(300_000))
    val progress = mutableListOf<Long>()
    val file = loader.download(artifact()) { progress += it }
    assertEquals(listOf(300_000L), source.offsets)
    assertEquals(300_000L, progress.first())
    assertEquals(content.size.toLong(), progress.last())
    assertArrayEquals(content, file.readBytes())
  }

  @Test
  fun aServerIgnoringTheRangeStartsTheFileOver() = runBlocking {
    val loader = downloader(FakeSource(content, ignoresRange = true))
    loader.partFile(artifact()).writeBytes(content.copyOf(300_000))
    assertArrayEquals(content, loader.download(artifact()) {}.readBytes())
  }

  @Test
  fun aDroppedConnectionKeepsThePartForNextTime() {
    val loader = downloader(FakeSource(content, dropAfter = 400_000))
    assertEquals(DownloadFailure.Network, failureOf { loader.download(artifact()) {} })
    assertEquals(400_000L, loader.partFile(artifact()).length())
    val source = FakeSource(content)
    val file = runBlocking { downloader(source).download(artifact()) {} }
    assertEquals(listOf(400_000L), source.offsets)
    assertArrayEquals(content, file.readBytes())
  }

  @Test
  fun anHttpErrorFailsWithItsStatus() {
    val loader = downloader(FakeSource(content, status = 404))
    assertEquals(DownloadFailure.Http(404), failureOf { loader.download(artifact()) {} })
  }

  @Test
  fun tooLittleStorageFailsBeforeAsking() {
    val source = FakeSource(content)
    val loader = downloader(source, free = content.size.toLong())
    loader.partFile(artifact()).writeBytes(content.copyOf(100_000))
    val failure = failureOf { loader.download(artifact()) {} }
    assertEquals(DownloadFailure.NotEnoughSpace(content.size - 100_000L), failure)
    assertTrue(source.offsets.isEmpty())
  }

  @Test
  fun aCompletePartIsVerifiedWithoutAsking() = runBlocking {
    val source = FakeSource(content)
    val loader = downloader(source)
    loader.partFile(artifact()).writeBytes(content)
    assertArrayEquals(content, loader.download(artifact()) {}.readBytes())
    assertTrue(source.offsets.isEmpty())
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}
