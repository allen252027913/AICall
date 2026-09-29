package me.fleey.ppocrv5.call

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProductScreenshotPayloadTest {
  @Test fun `size calculation matches serialized bytes for all padding lengths`() {
    for (size in listOf(1, 2, 3, 4, 5, 6, 1024, ProductScreenshotPayload.MAX_ENCODED_IMAGE_BYTES)) {
      val image = ByteArray(size) { 0xff.toByte() }
      val body = ProductScreenshotPayload.encode(image)
      assertEquals(ProductScreenshotPayload.requestBytes(size), body.size.toLong())
      assertArrayEquals(image, Base64.getDecoder().decode(JSONObject(String(body, Charsets.UTF_8)).getString("imageBase64")))
      assertTrue(body.size <= ProductScreenshotPayload.MAX_REQUEST_BYTES)
    }
  }

  @Test fun `one byte beyond image budget cannot fit JSON request budget`() {
    val size = ProductScreenshotPayload.MAX_ENCODED_IMAGE_BYTES + 1
    assertTrue(ProductScreenshotPayload.requestBytes(size) > ProductScreenshotPayload.MAX_REQUEST_BYTES)
    try {
      ProductScreenshotPayload.encode(ByteArray(size))
      fail("Expected size failure")
    } catch (_: IllegalArgumentException) { }
  }

  @Test fun `observed failing image produces request over three MiB`() {
    assertEquals(3_234_190L, ProductScreenshotPayload.requestBytes(2_425_628))
  }
}
