package me.fleey.ppocrv5.call

import org.json.JSONObject

data class EncodedProductFrame(
  val base64: String,
  val width: Int,
  val height: Int,
)

data class ProductRecognitionRequest(
  val apiName: String,
  val version: String,
  val dataParams: Map<String, String>,
  val responseType: String,
)

object ProductRecognitionRequestFactory {
  fun create(
    frame: EncodedProductFrame,
    requestTimeMillis: Long,
  ): ProductRecognitionRequest {
    require(frame.width > 0 && frame.height > 0) { "Encoded frame dimensions must be positive" }
    val params = JSONObject()
      .put("bizId", "666")
      .put("resId", RES_ID)
      .put("pageSize", 10)
      .put("from", "homeScan")
      .put("picBase64", frame.base64)
      .put("imgSize", "${frame.width},${frame.height}")
      .put("pageNumber", 1)
      .put("domainType", 0)
      .put("regionIndex", 1)
      .put("requestTime", requestTimeMillis.toString())
      .put("sourceItemId", "")
      .put("sourceSkuId", "")
      .put("isHongmeng", "0")
    return ProductRecognitionRequest(
      apiName = API_NAME,
      version = VERSION,
      dataParams = mapOf("params" to params.toString()),
      responseType = RESPONSE_TYPE,
    )
  }

  const val RES_ID = "32076707"
  const val API_NAME = "mtop.tmall.kangaroo.core.service.route.aldlampservicefixedresv2"
  const val VERSION = "1.0"
  const val RESPONSE_TYPE = "originaljson"
}
