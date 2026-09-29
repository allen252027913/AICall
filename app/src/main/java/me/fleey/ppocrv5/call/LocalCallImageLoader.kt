package me.fleey.ppocrv5.call

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Pure downsampling policy used before a private JPEG is decoded. */
object LocalCallImageSamplePolicy {
  fun sampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sampleSize = 1
    while (sourceWidth / (sampleSize * 2) >= targetWidth &&
      sourceHeight / (sampleSize * 2) >= targetHeight
    ) {
      sampleSize *= 2
    }
    return sampleSize
  }
}

/** Pixel dimensions after `ContentScale.Fit` has contained an image in a display box. */
data class LocalCallImageDimensions(val width: Int, val height: Int)

object LocalCallImageFitPolicy {
  fun fitWithin(
    sourceWidth: Int,
    sourceHeight: Int,
    containerWidth: Int,
    containerHeight: Int,
  ): LocalCallImageDimensions {
    if (sourceWidth <= 0 || sourceHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
      return LocalCallImageDimensions(1, 1)
    }
    val scale = minOf(
      containerWidth.toDouble() / sourceWidth,
      containerHeight.toDouble() / sourceHeight,
    )
    return LocalCallImageDimensions(
      width = (sourceWidth * scale).roundToInt().coerceAtLeast(1),
      height = (sourceHeight * scale).roundToInt().coerceAtLeast(1),
    )
  }
}

/** A synchronized byte-budget LRU cache so visible cards do not retain an unbounded bitmap set. */
class LocalCallImageMemoryCache<T : Any>(
  private val maxBytes: Long,
  private val sizeOf: (T) -> Long,
) {
  private val values = LinkedHashMap<String, CachedValue<T>>(0, 0.75f, true)
  private var cachedBytes = 0L

  init {
    require(maxBytes > 0L) { "maxBytes must be positive" }
  }

  @Synchronized fun get(key: String): T? = values[key]?.value

  @Synchronized fun put(key: String, value: T) {
    values.remove(key)?.let { cachedBytes -= it.bytes }
    val bytes = sizeOf(value).coerceAtLeast(0L)
    if (bytes > maxBytes) return
    values[key] = CachedValue(value, bytes)
    cachedBytes += bytes
    while (cachedBytes > maxBytes && values.isNotEmpty()) {
      val eldestKey = values.entries.iterator().next().key
      cachedBytes -= values.remove(eldestKey)?.bytes ?: 0L
    }
  }

  private data class CachedValue<T : Any>(val value: T, val bytes: Long)
}

private val decodedImageCache = LocalCallImageMemoryCache<ImageBitmap>(
  maxBytes = 16L * 1024L * 1024L,
  sizeOf = { image -> image.width.toLong() * image.height.toLong() * 2L },
)

@Composable
fun LocalCallCachedImage(
  imageFile: File,
  contentDescription: String,
  maxHeight: Dp,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val density = LocalDensity.current
    val targetWidth = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
    val targetHeight = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
    // Managed history filenames are immutable and unique; avoid main-thread file metadata reads.
    val cacheKey = "${imageFile.absolutePath}:$targetWidth:$targetHeight"
    var image by remember(cacheKey) { mutableStateOf(decodedImageCache.get(cacheKey)) }

    LaunchedEffect(cacheKey) {
      if (image == null) {
        image = withContext(Dispatchers.IO) {
          decodedImageCache.get(cacheKey) ?: decodeSampled(imageFile, targetWidth, targetHeight)?.also {
            decodedImageCache.put(cacheKey, it)
          }
        }
      }
    }

    image?.let {
      Image(
        bitmap = it,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = maxHeight)
          .background(LocalCallImagePlaceholder, RoundedCornerShape(12.dp)),
      )
    }
  }
}

internal fun decodeSampled(imageFile: File, targetWidth: Int, targetHeight: Int): ImageBitmap? {
  if (!imageFile.isFile) return null
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  val fittedTarget = LocalCallImageFitPolicy.fitWithin(
    sourceWidth = bounds.outWidth,
    sourceHeight = bounds.outHeight,
    containerWidth = targetWidth,
    containerHeight = targetHeight,
  )
  val bitmap = BitmapFactory.decodeFile(
    imageFile.absolutePath,
    BitmapFactory.Options().apply {
      inSampleSize = LocalCallImageSamplePolicy.sampleSize(
        bounds.outWidth,
        bounds.outHeight,
        fittedTarget.width,
        fittedTarget.height,
      )
      inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    },
  ) ?: return null
  return bitmap.asImageBitmap()
}

private val LocalCallImagePlaceholder = androidx.compose.ui.graphics.Color(0xFF292C32)
