package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProductCaptureRetryGuardTest {
  @Test
  fun `cancellation between retries prevents another capture and is not exhaustion`() {
    var active = true
    var captureCalls = 0
    val policy = ScreenCaptureRetryPolicy(
      maxAttempts = 3,
      retryDelayMillis = 10L,
      pause = { active = false },
    )

    val result = policy.captureWhileActive(
      isActive = { active },
      block = {
        captureCalls += 1
        null
      },
    )

    assertSame(ScreenCaptureRetryResult.Cancelled, result)
    assertEquals(1, captureCalls)
  }
}
