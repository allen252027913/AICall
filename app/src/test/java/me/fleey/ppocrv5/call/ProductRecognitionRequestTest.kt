package me.fleey.ppocrv5.call

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductRecognitionRequestTest {
  @Test
  fun `request preserves exact service and typed business contract`() {
    val request = ProductRecognitionRequestFactory.create(
      frame = EncodedProductFrame("abc123", 231, 512),
      requestTimeMillis = 1_787_024_190_779L,
    )

    assertEquals(
      "mtop.tmall.kangaroo.core.service.route.aldlampservicefixedresv2",
      request.apiName,
    )
    assertEquals("1.0", request.version)
    assertEquals(setOf("params"), request.dataParams.keys)
    assertEquals("originaljson", request.responseType)

    val params = JSONObject(request.dataParams.getValue("params"))
    assertEquals("666", params.getString("bizId"))
    assertEquals("32076707", params.getString("resId"))
    assertEquals(10, params.getInt("pageSize"))
    assertEquals("homeScan", params.getString("from"))
    assertEquals("abc123", params.getString("picBase64"))
    assertEquals("231,512", params.getString("imgSize"))
    assertEquals(1, params.getInt("pageNumber"))
    assertEquals(0, params.getInt("domainType"))
    assertEquals(1, params.getInt("regionIndex"))
    assertEquals("1787024190779", params.getString("requestTime"))
    assertEquals("", params.getString("sourceItemId"))
    assertEquals("", params.getString("sourceSkuId"))
    assertEquals("0", params.getString("isHongmeng"))
    assertEquals(
      setOf(
        "bizId",
        "resId",
        "pageSize",
        "from",
        "picBase64",
        "imgSize",
        "pageNumber",
        "domainType",
        "regionIndex",
        "requestTime",
        "sourceItemId",
        "sourceSkuId",
        "isHongmeng",
      ),
      params.keys().asSequence().toSet(),
    )
  }
}
