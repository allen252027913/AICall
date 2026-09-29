package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenUnderstandingEngineTest {
  private val engine = ScreenUnderstandingEngine()

  @Test
  fun `orders OCR blocks from top to bottom and removes duplicates`() {
    val blocks = listOf(
      ScreenTextBlock("立即购买", 0.95f, centerX = 800f, centerY = 1200f),
      ScreenTextBlock("商品详情", 0.98f, centerX = 300f, centerY = 120f),
      ScreenTextBlock("商品详情", 0.92f, centerX = 302f, centerY = 122f),
      ScreenTextBlock("¥ 99", 0.90f, centerX = 300f, centerY = 500f),
    )

    val snapshot = engine.understand(blocks, capturedAtMillis = 42L)

    assertEquals(listOf("商品详情", "¥ 99", "立即购买"), snapshot.lines)
    assertEquals(42L, snapshot.capturedAtMillis)
  }

  @Test
  fun `ignores low confidence and blank OCR`() {
    val snapshot = engine.understand(
      listOf(
        ScreenTextBlock("", 0.99f, 0f, 0f),
        ScreenTextBlock("噪声", 0.20f, 0f, 1f),
        ScreenTextBlock("设置", 0.88f, 0f, 2f),
      ),
      capturedAtMillis = 1L,
    )

    assertEquals(listOf("设置"), snapshot.lines)
    assertTrue(snapshot.summary.contains("设置"))
  }
}
