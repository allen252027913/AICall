package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccessibilityCommandSpeechPolicyTest {
  private val policy = AccessibilityCommandSpeechPolicy()

  @Test
  fun `acknowledgements describe an attempt without claiming completion`() {
    val commands = listOf(
      AccessibilityCommand.Swipe(SwipeDirection.UP),
      AccessibilityCommand.ClickListItem(3),
      AccessibilityCommand.ClickText("关注按钮"),
    )

    commands.forEach { command ->
      val match = policy.intentFor(command)
      assertEquals(LocalCallAction.ACCESSIBILITY_COMMAND, match.action)
      assertFalse(match.acknowledgement.contains("已"))
      assertFalse(match.acknowledgement.contains("成功"))
    }
  }

  @Test
  fun `result wording is based on callback outcome`() {
    val command = AccessibilityCommand.ClickText("关注按钮")

    assertEquals("已点击“关注按钮”。", policy.resultFor(command, succeeded = true))
    assertEquals(
      "没有找到可点击的“关注按钮”。",
      policy.resultFor(command, succeeded = false),
    )
  }
}
