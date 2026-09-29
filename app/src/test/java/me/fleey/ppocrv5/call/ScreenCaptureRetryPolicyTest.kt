package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenCaptureRetryPolicyTest {
  @Test
  fun `capture makes at most three attempts and returns the first frame`() {
    var attempts = 0
    val delays = mutableListOf<Long>()
    val policy = ScreenCaptureRetryPolicy(retryDelayMillis = 12L, pause = { delays += it })

    val result = policy.capture {
      attempts++
      if (attempts == 3) "frame" else null
    }

    assertEquals("frame", result)
    assertEquals(3, attempts)
    assertEquals(listOf(12L, 12L), delays)
  }

  @Test
  fun `capture returns null after three empty frames`() {
    var attempts = 0

    val result = ScreenCaptureRetryPolicy(pause = {}).capture<String> {
      attempts++
      null
    }

    assertNull(result)
    assertEquals(3, attempts)
  }
}
