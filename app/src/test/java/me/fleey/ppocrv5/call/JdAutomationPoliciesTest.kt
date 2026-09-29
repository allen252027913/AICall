package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JdAutomationPoliciesTest {
  @Test
  fun `targets and fallback coordinates stay locked to the JD flow`() {
    assertEquals("我的", JdTarget.MY.label)
    assertEquals("更多", JdTarget.MORE.label)
    assertEquals("一键领取", JdTarget.CLAIM_ALL.label)
    assertNull(JdFallbackTapPolicy.pointFor(JdTarget.MY))
    assertEquals(JdScreenPoint(916f, 475f), JdFallbackTapPolicy.pointFor(JdTarget.MORE))
    assertEquals(JdScreenPoint(500f, 574f), JdFallbackTapPolicy.pointFor(JdTarget.CLAIM_ALL))
  }

  @Test
  fun `node matching is normalized but exact`() {
    assertTrue(JdAccessibilityNodePolicy.matches(" 更\n多 ", null, JdTarget.MORE))
    assertTrue(JdAccessibilityNodePolicy.matches(null, "一键领取", JdTarget.CLAIM_ALL))
    assertFalse(JdAccessibilityNodePolicy.matches("查看更多", null, JdTarget.MORE))
    assertFalse(JdAccessibilityNodePolicy.matches("去领取", "领取中心", JdTarget.CLAIM_ALL))
  }

  @Test
  fun `only reliable Android resource IDs receive ID priority`() {
    assertEquals(
      JdNodeClickStrategy.VIEW_ID_THEN_NODE,
      JdAccessibilityNodePolicy.clickStrategy("com.jingdong.app.mall:id/more"),
    )
    assertEquals(
      JdNodeClickStrategy.NODE_OR_ANCESTOR,
      JdAccessibilityNodePolicy.clickStrategy("more"),
    )
    assertEquals(
      JdNodeClickStrategy.NODE_OR_ANCESTOR,
      JdAccessibilityNodePolicy.clickStrategy(null),
    )
  }

  @Test
  fun `ID candidate clicked through its ancestor is classified as accessibility node`() {
    var ancestorAttempts = 0

    var directAttempts = 0
    val method = JdNodeClickExecutor.execute(
      clickDirectNode = {
        directAttempts += 1
        directAttempts > 1
      },
      clickAncestorOnly = { ancestorAttempts += 1; true },
    )

    assertEquals(JdAutomationClickMethod.ACCESSIBILITY_NODE, method)
    assertEquals(1, directAttempts)
    assertEquals(1, ancestorAttempts)
  }

  @Test
  fun `clickable ID candidate is classified as ID without touching an ancestor`() {
    var ancestorAttempts = 0

    val method = JdNodeClickExecutor.execute(
      clickDirectNode = { true },
      clickAncestorOnly = { ancestorAttempts += 1; true },
    )

    assertEquals(JdAutomationClickMethod.VIEW_ID, method)
    assertEquals(0, ancestorAttempts)
  }

  @Test
  fun `click feedback truthfully distinguishes all methods`() {
    assertEquals(
      "已通过按钮 ID 点击「更多」",
      JdAutomationClickFeedback.format(JdTarget.MORE, JdAutomationClickMethod.VIEW_ID),
    )
    assertEquals(
      "已通过无障碍文字控件或可点击祖先点击「我的」",
      JdAutomationClickFeedback.format(JdTarget.MY, JdAutomationClickMethod.ACCESSIBILITY_NODE),
    )
    assertEquals(
      "已通过兜底坐标点击「一键领取」",
      JdAutomationClickFeedback.format(
        JdTarget.CLAIM_ALL,
        JdAutomationClickMethod.FALLBACK_COORDINATE,
      ),
    )
  }

  @Test
  fun `final speech distinguishes success from concrete failures`() {
    assertEquals(
      "京东打卡已完成，已点击“一键领取”。",
      JdAutomationResultFormatter.format(JdAutomationResult.COMPLETED),
    )
    assertEquals(
      "辅助功能尚未开启，请先在通话页开启后再试。",
      JdAutomationResultFormatter.format(JdAutomationResult.ACCESSIBILITY_DISABLED),
    )
    assertEquals(
      "没有检测到京东 App，请先安装后再试。",
      JdAutomationResultFormatter.format(JdAutomationResult.JD_NOT_INSTALLED),
    )
  }
}
