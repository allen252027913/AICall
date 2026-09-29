package me.fleey.ppocrv5.call

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReplyEngineTest {
  private val engine = LocalReplyEngine()

  @Test
  fun `answers screen question from latest local OCR snapshot`() {
    val reply = engine.reply(
      transcript = "这个页面是什么",
      snapshot = ScreenSnapshot(listOf("商品详情", "运动鞋", "¥ 99", "立即购买"), 7L),
    )

    assertTrue(reply.contains("商品详情"))
    assertTrue(reply.contains("运动鞋"))
    assertTrue(reply.contains("99"))
  }

  @Test
  fun `explains when no readable text is available`() {
    val reply = engine.reply("帮我看看屏幕", ScreenSnapshot(emptyList(), 8L))
    assertTrue(reply.contains("没有识别到清晰文字"))
  }

  @Test
  fun `keeps unsupported general chat explicitly local and bounded`() {
    val reply = engine.reply("写一首很长的诗", null)
    assertTrue(reply.contains("本地 Demo"))
  }
}
