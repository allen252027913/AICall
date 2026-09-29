package me.fleey.ppocrv5.call

/** Deliberately performs no I/O; a host may replace it through [ProductRecognitionGatewayProvider]. */
object UnavailableProductRecognitionGateway : ProductRecognitionGateway {
  override fun recognize(request: ProductRecognitionRequest): ProductRecognitionResult =
    ProductRecognitionResult.Unavailable(UNAVAILABLE_REASON)

  const val UNAVAILABLE_REASON = "独立版未配置商品识别服务"
}
