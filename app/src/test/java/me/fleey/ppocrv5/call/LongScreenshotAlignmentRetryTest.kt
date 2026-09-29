package me.fleey.ppocrv5.call

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LongScreenshotAlignmentRetryTest {
  @Test fun `temporary misalignment waits and accepts a later stable frame`() {
    val frames = ArrayDeque(listOf("uncertain", "matched"))
    var waits = 0
    val result = LongScreenshotAlignmentRetry().run(
      isActive = { true }, captureAndMatch = { frames.removeFirst() },
      isUncertain = { it == "uncertain" }, settle = { waits++; true },
    )
    assertEquals("matched", result)
    assertEquals(1, waits)
    assertTrue(frames.isEmpty())
  }

  @Test fun `missing retry frame does not turn a failed match into page bottom`() {
    var attempts = 0
    val result = LongScreenshotAlignmentRetry().run(
      isActive = { true }, captureAndMatch = { if (attempts++ == 0) "uncertain" else null },
      isUncertain = { it == "uncertain" }, settle = { true },
    )
    assertEquals("uncertain", result)
    assertEquals(3, attempts)
  }

  @Test fun `settle cancellation stops before another capture`() {
    var attempts = 0
    try {
      LongScreenshotAlignmentRetry().run(
        isActive = { true }, captureAndMatch = { attempts++; "uncertain" },
        isUncertain = { it == "uncertain" }, settle = { false },
      )
      fail("Expected cancellation")
    } catch (_: CancellationException) { assertEquals(1, attempts) }
  }

  @Test fun `matched and initially static frames do not add waits`() {
    for (initial in listOf("matched", "stationary", null)) {
      val result = LongScreenshotAlignmentRetry().run(
        isActive = { true }, captureAndMatch = { initial },
        isUncertain = { it == "uncertain" }, settle = { fail("Unexpected wait"); true },
      )
      assertEquals(initial, result)
    }
  }
}
