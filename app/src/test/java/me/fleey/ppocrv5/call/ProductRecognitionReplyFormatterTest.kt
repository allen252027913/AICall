package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductRecognitionReplyFormatterTest {
  @Test
  fun `success speaks only first product title and price`() {
    assertEquals(
      "晨光易擦白板笔，价格8.89元。",
      ProductRecognitionReplyFormatter.format(
        ProductRecognitionResult.Success(ProductSummary("晨光易擦白板笔", "8.89")),
      ),
    )
  }

  @Test
  fun `no product and failure use controlled replies`() {
    assertEquals(
      "暂时没有识别到这个商品，请换一个画面再试试。",
      ProductRecognitionReplyFormatter.format(ProductRecognitionResult.NoProduct),
    )
    assertEquals(
      "商品识别失败，请稍后再试。",
      ProductRecognitionReplyFormatter.format(ProductRecognitionResult.Failure),
    )
  }

  @Test
  fun `unavailable surfaces the configured reason exactly`() {
    assertEquals(
      "独立版未配置商品识别服务",
      ProductRecognitionReplyFormatter.format(
        ProductRecognitionResult.Unavailable("独立版未配置商品识别服务"),
      ),
    )
  }
}
