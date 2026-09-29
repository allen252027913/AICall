package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityCommandParserTest {
  private val parser = AccessibilityCommandParser()

  @Test
  fun `page and directional phrases map to normalized swipe directions`() {
    assertEquals(AccessibilityCommand.Swipe(SwipeDirection.UP), parser.parse("下一页"))
    assertEquals(AccessibilityCommand.Swipe(SwipeDirection.UP), parser.parse("向上滑"))
    assertEquals(AccessibilityCommand.Swipe(SwipeDirection.DOWN), parser.parse("向下滑动"))
    assertEquals(AccessibilityCommand.Swipe(SwipeDirection.LEFT), parser.parse("请向左滑"))
    assertEquals(AccessibilityCommand.Swipe(SwipeDirection.RIGHT), parser.parse("向右滑一下"))
    assertEquals(AccessibilityCommand.Swipe(SwipeDirection.DOWN), parser.parse("上一页"))
  }

  @Test
  fun `ordinal phrases use one based list item indexing`() {
    assertEquals(AccessibilityCommand.ClickListItem(1), parser.parse("第一个"))
    assertEquals(AccessibilityCommand.ClickListItem(3), parser.parse("第3个"))
    assertEquals(AccessibilityCommand.ClickListItem(3), parser.parse("点屏幕上第3个列表项"))
    assertEquals(AccessibilityCommand.ClickListItem(12), parser.parse("点第十二个"))
  }

  @Test
  fun `zero and missing ordinal targets are rejected`() {
    assertNull(parser.parse("点击第0个"))
    assertNull(parser.parse("点击第零个"))
    assertNull(parser.parse("点击列表项"))
  }

  @Test
  fun `click text removes the operation phrase but preserves the accessible label`() {
    assertEquals(AccessibilityCommand.ClickText("关注按钮"), parser.parse("点下关注按钮"))
    assertEquals(AccessibilityCommand.ClickText("立即购买"), parser.parse("请点击立即购买"))
  }

  @Test
  fun `non command speech is not claimed`() {
    assertNull(parser.parse("帮我看看当前屏幕"))
  }
}
