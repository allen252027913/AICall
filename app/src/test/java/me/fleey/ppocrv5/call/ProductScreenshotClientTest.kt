package me.fleey.ppocrv5.call

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProductScreenshotClientTest {
  private val png = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
  private val response = """{"anchor_name":"主播","live_time_desc":"今晚","product_list":[{"product_name":"商品","price_rule":"99元","live_time_desc":"今晚","product_image":"https://example.com/a.png"}],"knowledge_id":123,"knowledge_type":"product","knowledge_keyword":"商品"}"""

  @Test fun `posts script compatible JSON and returns validated recognition result`() {
    val connection = FakeConnection(200, response)
    val result = ProductScreenshotClient(connectionFactory = { url ->
      assertEquals("https://pre-tm-app-turbo.alibaba-inc.com/api/offline/product/analyze", url.toString())
      connection
    }).analyze(png)
    assertEquals("POST", connection.requestMethod)
    assertEquals("application/json", connection.getRequestProperty("Content-Type"))
    assertEquals("application/json", connection.getRequestProperty("Accept"))
    assertTrue(connection.getRequestProperty("X-Request-Id").isNotBlank())
    val payload = JSONObject(connection.sent.toString("UTF-8"))
    assertEquals(setOf("imageBase64"), payload.keys().asSequence().toSet())
    assertArrayEquals(png, Base64.getDecoder().decode(payload.getString("imageBase64")))
    assertEquals("主播", JSONObject(result.body).getString("anchor_name"))
    assertTrue(connection.closed)
  }

  @Test fun `HTTP error retains structured server response for logging`() {
    val connection = FakeConnection(503, """{"code":"MODEL_NOT_CONFIGURED"}""")
    try {
      ProductScreenshotClient(connectionFactory = { connection }).analyze(png)
      fail("Expected API failure")
    } catch (e: ProductScreenshotApiException) {
      assertEquals(503, e.status)
      assertTrue(e.body.contains("MODEL_NOT_CONFIGURED"))
      assertTrue(e.message!!.contains("PRODUCT_VISION_API_KEY"))
    }
    assertTrue(connection.closed)
  }

  @Test fun `HTML login page is not accepted as recognition success`() {
    try {
      ProductScreenshotClient(connectionFactory = { FakeConnection(200, "<html>Login</html>") }).analyze(png)
      fail("Expected invalid JSON")
    } catch (e: ProductScreenshotApiException) { assertTrue(e.message!!.contains("JSON")) }
  }

  @Test fun `HTML 413 reports request size rather than login or JSON failure`() {
    val connection = FakeConnection(413, "<html>413 Request Entity Too Large</html>")
    try {
      ProductScreenshotClient(connectionFactory = { connection }).analyze(png)
      fail("Expected request size failure")
    } catch (e: ProductScreenshotApiException) {
      assertEquals(413, e.status)
      assertTrue(e.message!!.contains("请求体"))
      assertFalse(e.message!!.contains("登录"))
      assertTrue(e.body.contains("Request Entity Too Large"))
    }
    assertTrue(connection.closed)
  }

  @Test fun `base64 JSON exceeding upload budget is rejected before connecting`() {
    val image = ByteArray(700 * 1024)
    png.copyInto(image)
    try {
      ProductScreenshotClient(connectionFactory = { fail("Must not connect"); FakeConnection(200, response) })
        .analyze(image)
      fail("Expected request body size rejection")
    } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("请求体")) }
  }

  @Test fun `valid JSON with wrong product schema is rejected`() {
    try {
      ProductScreenshotClient(connectionFactory = {
        FakeConnection(200, response.replace("\"price_rule\":\"99元\"", "\"price_rule\":99"))
      }).analyze(png)
      fail("Expected schema failure")
    } catch (e: ProductScreenshotApiException) { assertTrue(e.message!!.contains("price_rule")) }
  }

  @Test fun `oversized image fails before network connection`() {
    try {
      ProductScreenshotClient(connectionFactory = { fail("Must not connect"); FakeConnection(200, response) })
        .analyze(ByteArray(10 * 1024 * 1024 + 1))
      fail("Expected size rejection")
    } catch (_: IllegalArgumentException) { }
  }

  @Test fun `oversized response is bounded and connection released`() {
    val connection = FakeConnection(200, "x".repeat(1024 * 1024 + 1))
    try {
      ProductScreenshotClient(connectionFactory = { connection }).analyze(png)
      fail("Expected response size rejection")
    } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("1 MiB")) }
    assertTrue(connection.closed)
  }

  @Test fun `cancellation before submission never connects`() {
    val client = ProductScreenshotClient(connectionFactory = { fail("Must not connect"); FakeConnection(200, response) })
    client.cancel()
    try { client.analyze(png); fail("Expected cancellation") }
    catch (_: java.util.concurrent.CancellationException) { }
  }

  private class FakeConnection(private val status: Int, private val body: String) :
    HttpURLConnection(URL("https://example.com")) {
    val sent = ByteArrayOutputStream()
    var closed = false
    override fun connect() = Unit
    override fun disconnect() { closed = true }
    override fun usingProxy() = false
    override fun getOutputStream() = sent
    override fun getResponseCode() = status
    override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    override fun getErrorStream() = inputStream
  }
}
