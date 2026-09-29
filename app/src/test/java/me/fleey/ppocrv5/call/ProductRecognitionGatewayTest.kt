package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProductRecognitionGatewayTest {
  @Test
  fun `standalone default synchronously returns unavailable without inspecting request`() {
    val request = ProductRecognitionRequestFactory.create(
      EncodedProductFrame("frame", 100, 200),
      requestTimeMillis = 123L,
    )

    assertEquals(
      ProductRecognitionResult.Unavailable("独立版未配置商品识别服务"),
      UnavailableProductRecognitionGateway.recognize(request),
    )
  }

  @Test
  fun `provider defaults unavailable and accepts a future host injection`() {
    ProductRecognitionGatewayProvider.resetToDefault()
    assertSame(UnavailableProductRecognitionGateway, ProductRecognitionGatewayProvider.create())

    val injected = ProductRecognitionGateway { ProductRecognitionResult.NoProduct }
    ProductRecognitionGatewayProvider.install { injected }
    try {
      assertSame(injected, ProductRecognitionGatewayProvider.create())
    } finally {
      ProductRecognitionGatewayProvider.resetToDefault()
    }
  }
}
