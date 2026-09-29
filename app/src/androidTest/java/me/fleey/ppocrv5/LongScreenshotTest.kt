package me.fleey.ppocrv5

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import me.fleey.ppocrv5.call.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class LongScreenshotTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val page = IntArray(64 * 600) {
    val random = java.util.Random(it.toLong() * 10007)
    val gray = random.nextInt(256)
    0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
  }
  private fun frame(offset: Int): Bitmap = Bitmap.createBitmap(64, 240, Bitmap.Config.ARGB_8888).apply {
    setPixels(IntArray(64 * 240) { i ->
      when (val y = i / 64) {
        in 0..19 -> 0xff222222.toInt()
        in 220..239 -> 0xff888888.toInt()
        else -> page[(y - 20 + offset) * 64 + i % 64]
      }
    }, 0, 64, 0, 0, 64, 240)
  }

  @Test fun stitchedPixelsPreserveEachContentRowAndOnlyOneHeaderAndFooter() {
    LongScreenshotStitcher(context.cacheDir).use { stitcher ->
      stitcher.add(frame(0))
      assertEquals(LongScreenshotStitcher.Addition.APPENDED, stitcher.add(frame(97)))
      assertEquals(LongScreenshotStitcher.Addition.APPENDED, stitcher.add(frame(104)))
      val bitmap = stitcher.finish()
      try {
        assertEquals(344, bitmap.height)
        for (y in 0 until 344) for (x in 0 until 64) {
          val expected = when {
            y < 20 -> 0xff222222.toInt()
            y >= 324 -> 0xff888888.toInt()
            else -> page[(y - 20) * 64 + x]
          }
          assertEquals("pixel $x,$y", expected, bitmap.getPixel(x, y))
        }
      } finally { bitmap.recycle() }
    }
  }

  @Test fun scrollsBeyondTenFramesUntilThreeStationarySwipesAndCallerOwnsBitmap() {
    var captures = 0
    var scrolls = 0
    val result = WeChatLongScreenshotCapture(context.cacheDir).capture(
      isActive = { true }, captureFrame = { frame(20 * minOf(12, captures++)) },
      scroll = { scrolls++; true }, settle = { true },
    )
    try {
      assertEquals(16, captures)
      assertEquals(15, scrolls)
      assertEquals(13, result.frames)
      assertEquals(480, result.bitmap.height)
      assertFalse(result.bitmap.isRecycled)
    } finally { result.bitmap.recycle() }
  }

  @Test fun staticBottomMayStopProducingFramesAndStillCompletes() {
    var captures = 0
    var scrolls = 0
    var bottomCallbacks = 0
    val result = WeChatLongScreenshotCapture(context.cacheDir).capture(
      isActive = { true }, captureFrame = { if (captures++ == 0) frame(0) else null },
      scroll = { scrolls++; true }, settle = { true },
      onReachedBottom = { assertEquals(3, scrolls); bottomCallbacks++ },
    )
    try {
      assertEquals(3, scrolls)
      assertEquals(1, result.frames)
      assertEquals(240, result.bitmap.height)
      assertEquals(1, bottomCallbacks)
      assertTrue(result.reachedBottom)
    } finally { result.bitmap.recycle() }
  }

  @Test fun failedSwipeDoesNotReportBottomForPartialImage() {
    var bottomCallbacks = 0
    val result = WeChatLongScreenshotCapture(context.cacheDir).capture(
      isActive = { true }, captureFrame = { frame(0) },
      scroll = { false }, settle = { true }, onReachedBottom = { bottomCallbacks++ },
    )
    try {
      assertEquals(0, bottomCallbacks)
      assertFalse(result.reachedBottom)
    } finally { result.bitmap.recycle() }
  }

  @Test fun cancellationInBottomCallbackPreventsStitchCompletion() {
    var active = true
    try {
      WeChatLongScreenshotCapture(context.cacheDir).capture(
        isActive = { active }, captureFrame = { frame(0) },
        scroll = { true }, settle = { true }, onReachedBottom = { active = false },
      )
      fail("Expected cancellation before stitching")
    } catch (_: CancellationException) { assertFalse(active) }
  }

  @Test fun cancellationAfterGestureDoesNotCaptureOrPublishResult() {
    var active = true
    var captures = 0
    try {
      WeChatLongScreenshotCapture(context.cacheDir).capture(
        isActive = { active }, captureFrame = { captures++; frame(0) },
        scroll = { active = false; true }, settle = { true },
      )
      fail("Expected cancellation")
    } catch (_: CancellationException) { assertEquals(1, captures) }
  }

  @Test fun transientUnmatchedFrameRetriesAtSamePositionThenContinuesToBottom() {
    var captures = 0
    var scrolls = 0
    val result = WeChatLongScreenshotCapture(context.cacheDir).capture(
      isActive = { true },
      captureFrame = {
        when (captures++) {
          0 -> frame(0)
          1 -> frame(0).apply { eraseColor(0xffff0000.toInt()) }
          else -> frame(97)
        }
      },
      scroll = { scrolls++; true }, settle = { true },
    )
    try {
      assertEquals(6, captures)
      assertEquals(4, scrolls) // One advancing swipe + three stationary, no swipe during retry.
      assertEquals(2, result.frames)
      assertEquals(337, result.bitmap.height)
      assertTrue(result.reachedBottom)
    } finally { result.bitmap.recycle() }
  }

  @Test fun outputBudgetScalesWholeImageWithoutStoppingCaptureOrDroppingFooter() {
    LongScreenshotStitcher(context.cacheDir, maxPixels = 64L * 250).use { stitcher ->
      stitcher.add(frame(0))
      val next = frame(97)
      assertEquals(LongScreenshotStitcher.Addition.APPENDED, stitcher.add(next))
      val result = stitcher.finish()
      try {
        assertTrue(stitcher.outputWasScaled)
        assertTrue(result.width.toLong() * result.height <= 64L * 250)
        assertEquals(0xff888888.toInt(), result.getPixel(result.width / 2, result.height - 1))
        assertEquals(0xff222222.toInt(), result.getPixel(result.width / 2, 0))
      } finally { result.recycle() }
    }
  }

  @Test @SdkSuppress(minSdkVersion = 29)
  fun pngOriginalAndPublishedGalleryBytesMatch() {
    val bitmap = frame(0)
    val file = try { LongScreenshotStorage(context).saveBitmap(bitmap) } finally { bitmap.recycle() }
    var uri: Uri? = null
    try {
      val decoded = BitmapFactory.decodeFile(file.absolutePath)
      try { assertEquals(240, decoded.height) } finally { decoded.recycle() }
      val saved = RecordGalleryExporter(context).save(file, "image/png", "微信长截图").also { uri = it }
      assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(saved)!!.use { it.readBytes() })
      context.contentResolver.query(saved, arrayOf(MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
        assertTrue(it.moveToFirst())
        assertEquals("image/png", it.getString(0))
        assertEquals("Pictures/微信长截图/", it.getString(1))
        assertEquals(0, it.getInt(2))
      }
    } finally {
      uri?.let { context.contentResolver.delete(it, null, null) }
      file.delete()
    }
  }
}
