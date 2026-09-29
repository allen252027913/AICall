package me.fleey.ppocrv5.call

import org.junit.Assert.*
import org.junit.Test

class LongScreenshotProgressTest {
  @Test fun `continues beyond previous ten and fifty frame limits until repeated stationary frames`() {
    val progress = LongScreenshotProgress()
    repeat(80) { progress.advanced(); assertFalse(progress.reachedBottom) }
    assertEquals(81, progress.frames)
    repeat(2) { progress.stationary(); assertFalse(progress.reachedBottom) }
    progress.stationary()
    assertTrue(progress.reachedBottom)
  }

  @Test fun `late loaded content resets bottom confirmation`() {
    val progress = LongScreenshotProgress()
    progress.stationary()
    progress.stationary()
    progress.advanced()
    progress.stationary()
    assertFalse(progress.reachedBottom)
  }

  @Test fun `very tall full image scales into bitmap budget without trimming its bottom`() {
    val size = longScreenshotOutputSize(1000, 100_000L, 1_000_000L)
    assertEquals(100, size.first)
    assertEquals(10_000, size.second)
    assertEquals(200 to 400, longScreenshotOutputSize(200, 400L, 1_000_000L))
    val tall = longScreenshotOutputSize(1000, 1_000_000L, 12_000_000L)
    assertEquals(30 to 30_000, tall)
  }
}
