package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalCallPipWindowStatePolicyTest {
  @Test
  fun `restores secure flag states that existed before pip and before capture hiding`() {
    val policy = LocalCallPipWindowStatePolicy()

    assertEquals(true, policy.onPipEnter(previouslySecure = true))
    assertEquals(false, policy.onCaptureHide(currentlySecure = true))
    assertEquals(true, policy.onCaptureRestore())
    assertEquals(true, policy.onPipExit())
  }

  @Test
  fun `restores an insecure pre-pip window after capture hiding`() {
    val policy = LocalCallPipWindowStatePolicy()

    assertEquals(true, policy.onPipEnter(previouslySecure = false))
    assertEquals(false, policy.onCaptureHide(currentlySecure = true))
    assertEquals(true, policy.onCaptureRestore())
    assertEquals(false, policy.onPipExit())
  }
}
