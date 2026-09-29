package me.fleey.ppocrv5.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductImageEncoderTest {
  @Test
  fun `target size scales landscape and portrait while preserving aspect ratio`() {
    assertEquals(ImageSize(640, 320), ProductImageEncodingPolicy.targetSize(1280, 640))
    assertEquals(ImageSize(320, 640), ProductImageEncodingPolicy.targetSize(640, 1280))
  }

  @Test
  fun `target size never upscales a small image`() {
    assertEquals(ImageSize(231, 512), ProductImageEncodingPolicy.targetSize(231, 512))
  }

  @Test
  fun `quality search descends until payload is within eighty kibibytes`() {
    val visited = mutableListOf<Int>()
    val selected = ProductImageEncodingPolicy.selectQuality(80 * 1024) { quality ->
      visited += quality
      if (quality > 85) 90 * 1024 else 70 * 1024
    }

    assertEquals(85, selected)
    assertEquals(listOf(100, 95, 90, 85), visited)
  }

  @Test
  fun `quality search returns null when even zero quality is too large`() {
    assertNull(ProductImageEncodingPolicy.selectQuality(80 * 1024) { 81 * 1024 })
  }

  @Test
  fun `encoding core reports scaled dimensions keeps source and recycles temporary`() {
    val source = FakeFrame(width = 1280, height = 640, bytesAtQuality = { byteArrayOf(1, 2, 3) })

    val encoded = ProductImageEncodingCore().encode(source)

    assertArrayEquals(byteArrayOf(1, 2, 3), encoded.jpegBytes)
    assertEquals(640, encoded.width)
    assertEquals(320, encoded.height)
    assertFalse(source.recycled)
    assertTrue(source.scaledFrame?.recycled == true)
  }

  @Test
  fun `encoding failure still keeps source and recycles temporary`() {
    val source = FakeFrame(
      width = 1200,
      height = 800,
      bytesAtQuality = { ByteArray(81 * 1024) },
    )

    val failure = runCatching { ProductImageEncodingCore().encode(source) }.exceptionOrNull()

    assertTrue(failure is ProductImageEncodingException)
    assertFalse(source.recycled)
    assertTrue(source.scaledFrame?.recycled == true)
  }

  private class FakeFrame(
    override val width: Int,
    override val height: Int,
    private val bytesAtQuality: (Int) -> ByteArray,
  ) : ProductImageFrame {
    var recycled = false
    var scaledFrame: FakeFrame? = null

    override fun scaled(width: Int, height: Int): ProductImageFrame = FakeFrame(
      width = width,
      height = height,
      bytesAtQuality = bytesAtQuality,
    ).also { scaledFrame = it }

    override fun encodeJpeg(quality: Int): ByteArray = bytesAtQuality(quality)

    override fun recycle() {
      recycled = true
    }
  }
}
