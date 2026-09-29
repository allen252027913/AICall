package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeChatKeyboardTapPolicyTest {
  @Test
  fun `tall phone fallback types lijiaqi selects the first candidate and searches`() {
    val plan = WeChatKeyboardTapPolicy.planFor(screenWidth = 1_220, screenHeight = 2_700)

    requireNotNull(plan)
    assertPoint(plan.queryInput, expectedX = 610f, expectedY = 219.6f)
    assertEquals(6, plan.pinyinKeys.size)
    assertPoint(plan.pinyinKeys[0], expectedX = 1_079.7f, expectedY = 2_150.01f)
    assertPoint(plan.pinyinKeys[1], expectedX = 908.9f, expectedY = 1_979.91f)
    assertPoint(plan.pinyinKeys[2], expectedX = 835.7f, expectedY = 2_150.01f)
    assertPoint(plan.pinyinKeys[3], expectedX = 103.7f, expectedY = 2_150.01f)
    assertPoint(plan.pinyinKeys[4], expectedX = 61f, expectedY = 1_979.91f)
    assertPoint(plan.pinyinKeys[5], expectedX = 908.9f, expectedY = 1_979.91f)
    assertPoint(plan.firstCandidate, expectedX = 140.3f, expectedY = 1_800.09f)
    assertPoint(plan.keyboardSearch, expectedX = 1_110.2f, expectedY = 2_519.91f)
  }

  @Test
  fun `keyboard fallback rejects unavailable display dimensions`() {
    assertNull(WeChatKeyboardTapPolicy.planFor(screenWidth = 0, screenHeight = 2_700))
    assertNull(WeChatKeyboardTapPolicy.planFor(screenWidth = 1_220, screenHeight = 0))
  }

  private fun assertPoint(
    actual: WeChatScreenPoint,
    expectedX: Float,
    expectedY: Float,
  ) {
    assertEquals(expectedX, actual.x, 0.01f)
    assertEquals(expectedY, actual.y, 0.01f)
  }
}
