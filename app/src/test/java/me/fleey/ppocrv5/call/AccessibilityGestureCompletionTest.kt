package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityGestureCompletionTest {
  @Test
  fun `watchdog timeout has a distinct terminal effect and ignores a late completion`() {
    val effects = mutableListOf<String>()
    val completion = AccessibilityGestureCompletion(
      onCompleted = { effects += "completed" },
      onFailed = { effects += "failed" },
      onTimedOut = { effects += "timed-out" },
    )

    assertTrue(completion.onDispatchResult(accepted = true))
    completion.onTimedOut()
    completion.onCompleted()
    completion.onCancelled()

    assertEquals(listOf("timed-out"), effects)
  }
}
