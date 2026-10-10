package io.jeph.pipkin.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import io.jeph.pipkin.agent.ImagePart
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Image decoding for the composer and the chat stream; all work runs off the main thread. */
internal object ChatImages {
  private const val PROMPT_EDGE = 1568
  private const val THUMBNAIL_EDGE = 512
  private const val JPEG_QUALITY = 85
  private const val JPEG = "image/jpeg"

  /**
   * Reads [uri] for a prompt: the long edge is scaled to at most 1568px and the image is re-encoded
   * as JPEG 85 with transparency flattened on white. Null when the image cannot be read.
   */
  suspend fun attach(context: Context, uri: Uri): Attachment.Image? = withContext(Dispatchers.IO) {
    try {
      // Software pixels: the bitmap is drawn into a Canvas to flatten transparency.
      val bitmap = decode(ImageDecoder.createSource(context.contentResolver, uri), PROMPT_EDGE, software = true)
      val bytes = ByteArrayOutputStream().use { out ->
        flattenOnWhite(bitmap).compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        out.toByteArray()
      }
      val thumbnail = decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), THUMBNAIL_EDGE, software = false)
      Attachment.Image(ImagePart(Base64.encodeToString(bytes, Base64.NO_WRAP), JPEG), thumbnail.asImageBitmap())
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }

  /** A thumbnail of an image from the chat history, long edge at most 512px; null when it cannot be decoded. */
  suspend fun thumbnail(part: ImagePart): ImageBitmap? = withContext(Dispatchers.Default) {
    try {
      val bytes = Base64.decode(part.data, Base64.DEFAULT)
      decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), THUMBNAIL_EDGE, software = false).asImageBitmap()
    } catch (_: IOException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  /** Decodes [source] downsampled so its long edge is at most [maxEdge]; smaller images keep their size. */
  private fun decode(source: ImageDecoder.Source, maxEdge: Int, software: Boolean): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      val width = info.size.width
      val height = info.size.height
      val longEdge = max(width, height)
      if (longEdge > maxEdge) {
        val scale = maxEdge.toFloat() / longEdge
        decoder.setTargetSize(
          (width * scale).roundToInt().coerceAtLeast(1),
          (height * scale).roundToInt().coerceAtLeast(1),
        )
      }
      if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

  private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val flat = createBitmap(bitmap.width, bitmap.height)
    Canvas(flat).apply {
      drawColor(Color.WHITE)
      drawBitmap(bitmap, 0f, 0f, null)
    }
    return flat
  }
}
