package me.fleey.ppocrv5.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatAutomationButtonPolicyTest {
  @Test
  fun `button is enabled only while prerequisites are active and the call is listening`() {
    assertTrue(
      WeChatAutomationButtonPolicy.isEnabled(
        callActive = true,
        screenSharing = true,
        accessibilityEnabled = true,
        phase = CallPhase.LISTENING,
      ),
    )
    assertFalse(WeChatAutomationButtonPolicy.isEnabled(true, true, true, CallPhase.PROCESSING))
    assertFalse(WeChatAutomationButtonPolicy.isEnabled(true, true, true, CallPhase.SPEAKING))
    assertFalse(WeChatAutomationButtonPolicy.isEnabled(false, true, true, CallPhase.LISTENING))
    assertFalse(WeChatAutomationButtonPolicy.isEnabled(true, false, true, CallPhase.LISTENING))
    assertFalse(WeChatAutomationButtonPolicy.isEnabled(true, true, false, CallPhase.LISTENING))
  }
}
