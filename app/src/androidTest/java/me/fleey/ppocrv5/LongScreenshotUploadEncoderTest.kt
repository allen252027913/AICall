package me.fleey.ppocrv5

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import me.fleey.ppocrv5.call.LongScreenshotUploadEncoder
import me.fleey.ppocrv5.call.ProductScreenshotPayload
import org.junit.Assert.*
import org.junit.Test

class LongScreenshotUploadEncoderTest {
  @Test fun uploadKeepsLongImageDimensionsAndCallerBitmapOwnership() {
    val source = Bitmap.createBitmap(80, 1200, Bitmap.Config.ARGB_8888)
    source.eraseColor(0xffaabbcc.toInt())
    try {
      val bytes = LongScreenshotUploadEncoder.encode(source)
      assertTrue(ProductScreenshotPayload.requestBytes(bytes.size) <= ProductScreenshotPayload.MAX_REQUEST_BYTES)
      assertEquals(0xff.toByte(), bytes[0])
      assertEquals(0xd8.toByte(), bytes[1])
      val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      try {
        assertEquals(80, decoded.width)
        assertEquals(1200, decoded.height)
      } finally { decoded.recycle() }
      assertFalse(source.isRecycled)
    } finally { source.recycle() }
  }

  @Test fun largeDetailedImageFitsRequestBudgetWithoutCroppingOrRecyclingSource() {
    val source = Bitmap.createBitmap(600, 8000, Bitmap.Config.ARGB_8888)
    val random = java.util.Random(42)
    val row = IntArray(source.width)
    for (y in 0 until source.height) {
      for (x in row.indices) row[x] = random.nextInt() or 0xff000000.toInt()
      source.setPixels(row, 0, source.width, 0, y, source.width, 1)
    }
    val firstPixel = source.getPixel(0, 0)
    try {
      val upload = LongScreenshotUploadEncoder.prepare(source)
      assertTrue(ProductScreenshotPayload.requestBytes(upload.bytes.size) <= ProductScreenshotPayload.MAX_REQUEST_BYTES)
      assertTrue(upload.width in 256 until source.width)
      assertEquals(source.height.toDouble() / source.width, upload.height.toDouble() / upload.width, 0.01)
      val decoded = BitmapFactory.decodeByteArray(upload.bytes, 0, upload.bytes.size)
      try {
        assertEquals(upload.width, decoded.width)
        assertEquals(upload.height, decoded.height)
      } finally { decoded.recycle() }
      assertFalse(source.isRecycled)
      assertEquals(firstPixel, source.getPixel(0, 0))
    } finally { source.recycle() }
  }
}
