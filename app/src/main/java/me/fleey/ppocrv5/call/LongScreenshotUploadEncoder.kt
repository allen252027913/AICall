package me.fleey.ppocrv5.call

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

data class LongScreenshotUpload(val bytes: ByteArray, val width: Int, val height: Int, val quality: Int)

/** Keeps every row. Does not mutate or recycle the caller's original bitmap. */
object LongScreenshotUploadEncoder {
  fun encode(bitmap: Bitmap): ByteArray = prepare(bitmap).bytes

  fun prepare(bitmap: Bitmap): LongScreenshotUpload {
    var current = bitmap
    val minimumWidth = minOf(bitmap.width, 256)
    try {
      while (true) {
        for (quality in intArrayOf(90, 80, 65, 50)) {
          if (Thread.currentThread().isInterrupted) throw CancellationException("长截图识别已取消")
          LimitedImageOutput().use { output ->
            if (!current.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
              throw IOException("长图 JPEG 编码失败")
            }
            if (!output.overflow) return LongScreenshotUpload(output.toByteArray(), current.width, current.height, quality)
          }
        }
        // Do not shrink already narrow screenshots into unreadable strips.
        if (current.width <= minimumWidth) break
        val width = maxOf(minimumWidth, (current.width * 0.8).toInt())
        val height = maxOf(1, (bitmap.height.toDouble() * width / bitmap.width).roundToInt())
        if (current !== bitmap) current.recycle()
        // Always resample the original to avoid cumulative resampling loss.
        current = Bitmap.createScaledBitmap(bitmap, width, height, true)
      }
      throw IOException("长图压缩后请求体仍超过 ${ProductScreenshotPayload.MAX_REQUEST_BYTES} bytes 预算；为保留文字清晰度停止缩小，请提高网关与服务端请求体上限")
    } finally {
      if (current !== bitmap && !current.isRecycled) current.recycle()
    }
  }

  /** Discard overflowing writes instead of throwing through Bitmap.compress's native callback. */
  private class LimitedImageOutput : ByteArrayOutputStream() {
    var overflow = false
      private set
    override fun write(b: Int) {
      if (overflow) return
      if (count >= ProductScreenshotPayload.MAX_ENCODED_IMAGE_BYTES) overflow = true
      else super.write(b)
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
      if (overflow) return
      if (len > ProductScreenshotPayload.MAX_ENCODED_IMAGE_BYTES - count) overflow = true
      else super.write(b, off, len)
    }
  }
}
