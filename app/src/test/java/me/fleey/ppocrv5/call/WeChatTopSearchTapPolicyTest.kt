package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatTopSearchTapPolicyTest {
  @Test
  fun `top search point stays in the toolbar on a tall phone screen`() {
    val point = WeChatTopSearchTapPolicy.pointFor(screenWidth = 1_220, screenHeight = 2_700)

    requireNotNull(point)
    assertEquals(1_012.6f, point.x, 0.01f)
    assertEquals(189.95f, point.y, 0.01f)
  }

  @Test
  fun `top search point rejects unavailable display dimensions`() {
    assertNull(WeChatTopSearchTapPolicy.pointFor(screenWidth = 0, screenHeight = 2_340))
    assertNull(WeChatTopSearchTapPolicy.pointFor(screenWidth = 1_080, screenHeight = 0))
  }

  @Test
  fun `position tap wins before a search node can report a false positive click`() {
    var accessibleNodeClicked = false

    val method = WeChatTopSearchClickStrategy.execute(
      clickAccessibleNode = { accessibleNodeClicked = true; true },
      dispatchPositionTap = { true },
    )

    assertEquals(WeChatTopSearchClickMethod.POSITION_GESTURE, method)
    assertFalse(accessibleNodeClicked)
  }

  @Test
  fun `position tap is dispatched when search node is unavailable`() {
    val method = WeChatTopSearchClickStrategy.execute(
      clickAccessibleNode = { false },
      dispatchPositionTap = { true },
    )

    assertEquals(WeChatTopSearchClickMethod.POSITION_GESTURE, method)
  }
}
