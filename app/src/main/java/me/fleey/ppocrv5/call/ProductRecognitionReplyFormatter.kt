package me.fleey.ppocrv5.call

object ProductRecognitionReplyFormatter {
  fun format(result: ProductRecognitionResult): String = when (result) {
    is ProductRecognitionResult.Success ->
      "${result.summary.title}，价格${result.summary.price}元。"
    ProductRecognitionResult.NoProduct ->
      "暂时没有识别到这个商品，请换一个画面再试试。"
    ProductRecognitionResult.Failure ->
      "商品识别失败，请稍后再试。"
    is ProductRecognitionResult.Unavailable -> result.reason
  }
}
