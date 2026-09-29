package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallTurnPolicyTest {
  private val policy = LocalCallTurnPolicy()

  @Test
  fun `wechat button query starts product capture automation`() {
    val match = policy.match("微信商品关键帧")

    assertEquals(LocalCallAction.WECHAT_PRODUCT_CAPTURE, match?.action)
    assertEquals("好的，我现在去微信打开商品并截取关键帧。", match?.acknowledgement)
  }

  @Test
  fun `jd check in returns immediate acknowledgement`() {
    val match = policy.match("去京东打卡")

    assertEquals(LocalCallAction.JD_CHECK_IN, match?.action)
    assertEquals("好的，我现在帮你去京东打卡。", match?.acknowledgement)
  }

  @Test
  fun `jd check in ignores punctuation and wins intent priority`() {
    assertEquals(
      LocalCallAction.JD_CHECK_IN,
      policy.match("帮我，去京东打卡！帮我看一下这个商品")?.action,
    )
  }

  @Test
  fun `product request returns immediate acknowledgement`() {
    val match = policy.match("帮我看一下这个商品")

    assertEquals(LocalCallAction.RECOGNIZE_PRODUCT, match?.action)
    assertEquals("好的，我现在帮你识别这个商品。", match?.acknowledgement)
  }

  @Test
  fun `product request ignores punctuation and wins over screen reading`() {
    assertEquals(
      LocalCallAction.RECOGNIZE_PRODUCT,
      policy.match("帮我看一下，这个商品！")?.action,
    )
  }

  @Test
  fun `screen intent returns immediate acknowledgement`() {
    val match = policy.match("帮我看一下当前页面")

    assertEquals(LocalCallAction.READ_SCREEN, match?.action)
    assertEquals("好的，我现在替你查看当前屏幕。", match?.acknowledgement)
  }

  @Test
  fun `unsupported speech stays silent`() {
    assertNull(policy.match("你好今天天气怎么样"))
  }

  @Test
  fun `only complete stop command interrupts in real time`() {
    val spoken = "正在播报"

    assertTrue(policy.shouldInterrupt("先停一下", spoken))
    assertTrue(policy.shouldInterrupt("先停，一下！", spoken))
    assertFalse(policy.shouldInterrupt("正在播报，先停一下", spoken))
    assertFalse(policy.shouldInterrupt("先停一下谢谢", spoken))
    assertFalse(policy.shouldInterrupt("停一下", spoken))
    assertFalse(policy.shouldInterrupt("别说了", spoken))
    assertFalse(policy.shouldInterrupt("我还有一个问题", spoken))
    assertFalse(policy.shouldInterrupt(spoken, spoken))
  }
}
