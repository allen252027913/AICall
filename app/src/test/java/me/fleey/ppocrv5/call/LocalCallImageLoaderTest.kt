package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCallImageLoaderTest {
  @Test
  fun `sample policy uses a power of two without undersizing the target`() {
    assertEquals(2, LocalCallImageSamplePolicy.sampleSize(4000, 3000, 1200, 800))
  }

  @Test
  fun `sample policy leaves images smaller than their display bounds untouched`() {
    assertEquals(1, LocalCallImageSamplePolicy.sampleSize(800, 600, 1200, 800))
  }

  @Test
  fun `portrait source samples against its aspect ratio fitted target`() {
    val fitted = LocalCallImageFitPolicy.fitWithin(
      sourceWidth = 1080,
      sourceHeight = 2400,
      containerWidth = 1080,
      containerHeight = 960,
    )

    assertEquals(LocalCallImageDimensions(width = 432, height = 960), fitted)
    assertEquals(2, LocalCallImageSamplePolicy.sampleSize(1080, 2400, fitted.width, fitted.height))
  }

  @Test
  fun `fit policy keeps landscape source inside portrait bounds`() {
    assertEquals(
      LocalCallImageDimensions(width = 1080, height = 486),
      LocalCallImageFitPolicy.fitWithin(
        sourceWidth = 2400,
        sourceHeight = 1080,
        containerWidth = 1080,
        containerHeight = 960,
      ),
    )
  }

  @Test
  fun `byte budget cache evicts the least recently used bitmap`() {
    val cache = LocalCallImageMemoryCache<String>(maxBytes = 8) { it.length.toLong() }
    cache.put("first", "one")
    cache.put("second", "two")
    cache.get("first")
    cache.put("third", "three")

    assertEquals("one", cache.get("first"))
    assertNull(cache.get("second"))
    assertEquals("three", cache.get("third"))
  }
}
