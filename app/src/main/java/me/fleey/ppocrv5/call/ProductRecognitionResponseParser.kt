package me.fleey.ppocrv5.call

import org.json.JSONObject

sealed interface ProductRecognitionParseResult {
  data class Success(val summary: ProductSummary) : ProductRecognitionParseResult
  data object NoProduct : ProductRecognitionParseResult
  data object Invalid : ProductRecognitionParseResult
}

object ProductRecognitionResponseParser {
  fun parseFirstProduct(json: String): ProductSummary? =
    (parse(json) as? ProductRecognitionParseResult.Success)?.summary

  fun parse(json: String): ProductRecognitionParseResult = try {
    val root = JSONObject(json)
    val data = root.optJSONObject("data") ?: root
    val resultValue = data.optJSONObject("resultValue")
      ?: return ProductRecognitionParseResult.Invalid
    val routeResult = resultValue.optJSONObject(ProductRecognitionRequestFactory.RES_ID)
      ?: return ProductRecognitionParseResult.Invalid
    val pages = routeResult.optJSONArray("data")
      ?: return ProductRecognitionParseResult.Invalid
    if (pages.length() == 0) return ProductRecognitionParseResult.NoProduct
    val firstPage = pages.optJSONObject(0) ?: return ProductRecognitionParseResult.Invalid
    val products = firstPage.optJSONArray("productList")
      ?: return ProductRecognitionParseResult.NoProduct
    if (products.length() == 0) return ProductRecognitionParseResult.NoProduct
    val product = products.optJSONObject(0) ?: return ProductRecognitionParseResult.Invalid
    val title = product.stringValue("titleNew")
      .ifBlank { product.stringValue("shortTitle") }
    val price = product.stringValue("priceIntString") + product.stringValue("priceDecimalString")
    if (title.isBlank() || price.isBlank()) {
      ProductRecognitionParseResult.Invalid
    } else {
      ProductRecognitionParseResult.Success(ProductSummary(title, price))
    }
  } catch (_: Throwable) {
    ProductRecognitionParseResult.Invalid
  }

  private fun JSONObject.stringValue(key: String): String =
    if (has(key) && !isNull(key)) optString(key, "").trim() else ""
}
