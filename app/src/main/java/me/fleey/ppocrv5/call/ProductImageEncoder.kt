package me.fleey.ppocrv5.call

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class ImageSize(
  val width: Int,
  val height: Int,
)

data class EncodedProductBytes(
  val jpegBytes: ByteArray,
  val width: Int,
  val height: Int,
)

interface ProductImageFrame {
  val width: Int
  val height: Int
  fun scaled(width: Int, height: Int): ProductImageFrame
  fun encodeJpeg(quality: Int): ByteArray
  fun recycle()
}

class ProductImageEncodingException(message: String, cause: Throwable? = null) :
  IllegalStateException(message, cause)

object ProductImageEncodingPolicy {
  private const val MAX_SIDE = 640

  fun targetSize(width: Int, height: Int): ImageSize {
    require(width > 0 && height > 0) { "图片尺寸无效" }
    val longestSide = maxOf(width, height)
    if (longestSide <= MAX_SIDE) return ImageSize(width, height)
    val scale = MAX_SIDE.toDouble() / longestSide
    return ImageSize(
      width = (width * scale).roundToInt().coerceAtLeast(1),
      height = (height * scale).roundToInt().coerceAtLeast(1),
    )
  }

  fun selectQuality(maxBytes: Int, encodedSize: (quality: Int) -> Int): Int? {
    require(maxBytes > 0) { "图片大小限制无效" }
    var quality = 100
    while (true) {
      if (encodedSize(quality) <= maxBytes) return quality
      if (quality == 0) return null
      quality = if (quality <= 10) quality - 1 else quality - 5
    }
  }
}

/** Platform-neutral compression loop so bounds and ownership can be verified on the JVM. */
class ProductImageEncodingCore(
  private val maxBytes: Int = MAX_BYTES,
) {
  fun encode(source: ProductImageFrame): EncodedProductBytes {
    val target = ProductImageEncodingPolicy.targetSize(source.width, source.height)
    val uploadFrame = if (target.width == source.width && target.height == source.height) {
      source
    } else {
      source.scaled(target.width, target.height)
    }
    try {
      var acceptedBytes = ByteArray(0)
      val quality = try {
        ProductImageEncodingPolicy.selectQuality(maxBytes) { candidateQuality ->
          acceptedBytes = uploadFrame.encodeJpeg(candidateQuality)
          acceptedBytes.size
        }
      } catch (error: Throwable) {
        throw ProductImageEncodingException("当前画面编码失败", error)
      }
      if (quality == null) throw ProductImageEncodingException("当前画面压缩后仍然过大")
      return EncodedProductBytes(
        jpegBytes = acceptedBytes,
        width = uploadFrame.width,
        height = uploadFrame.height,
      )
    } finally {
      if (uploadFrame !== source) uploadFrame.recycle()
    }
  }

  companion object {
    const val MAX_BYTES = 80 * 1024
  }
}

class ProductImageEncoder(
  private val core: ProductImageEncodingCore = ProductImageEncodingCore(),
) {
  fun encode(source: Bitmap): EncodedProductFrame {
    val encoded = core.encode(AndroidBitmapFrame(source))
    return EncodedProductFrame(
      base64 = Base64.encodeToString(encoded.jpegBytes, Base64.NO_WRAP),
      width = encoded.width,
      height = encoded.height,
    )
  }

  private class AndroidBitmapFrame(
    private val bitmap: Bitmap,
  ) : ProductImageFrame {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun scaled(width: Int, height: Int): ProductImageFrame =
      AndroidBitmapFrame(Bitmap.createScaledBitmap(bitmap, width, height, true))

    override fun encodeJpeg(quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
        throw ProductImageEncodingException("当前画面编码失败")
      }
      output.toByteArray()
    }

    override fun recycle() {
      bitmap.recycle()
    }
  }
}
