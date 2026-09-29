package me.fleey.ppocrv5.call

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

data class ProductScreenshotResponse(val body: String, val elapsedMillis: Long)
class ProductScreenshotApiException(val status: Int, val body: String, message: String) : IOException(message)

/** One cancellable request, mirroring test-product-screenshot.py. No credentials in the client. */
class ProductScreenshotClient(
  private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
  val requestId: String = UUID.randomUUID().toString()
  private val cancelled = AtomicBoolean()
  private val activeConnection = AtomicReference<HttpURLConnection?>()

  fun cancel() {
    cancelled.set(true)
    runCatching { activeConnection.getAndSet(null)?.disconnect() }
  }

  fun analyze(image: ByteArray): ProductScreenshotResponse {
    checkActive()
    require(image.isNotEmpty() && image.size <= MAX_IMAGE_BYTES) { "图片必须非空且不超过 10 MiB" }
    val png = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
    val isPng = image.size >= png.size && png.indices.all { image[it] == png[it] }
    val isJpeg = image.size >= 3 && image[0] == 0xff.toByte() && image[1] == 0xd8.toByte() && image[2] == 0xff.toByte()
    require(isPng || isJpeg) { "仅支持 PNG/JPEG 图片" }
    val payload = ProductScreenshotPayload.encode(image)
    checkActive()
    val started = System.nanoTime()
    val connection = connectionFactory(URL(ENDPOINT))
    activeConnection.set(connection)
    try {
      checkActive()
      connection.requestMethod = "POST"
      connection.connectTimeout = TIMEOUT_MILLIS
      connection.readTimeout = TIMEOUT_MILLIS
      connection.instanceFollowRedirects = false
      connection.useCaches = false
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("X-Request-Id", requestId)
      connection.setFixedLengthStreamingMode(payload.size)
      connection.outputStream.use { output ->
        var offset = 0
        while (offset < payload.size) {
          checkActive()
          val count = minOf(8192, payload.size - offset)
          output.write(payload, offset, count)
          offset += count
        }
      }
      checkActive()
      val status = connection.responseCode
      val stream = if (status >= 400) connection.errorStream else connection.inputStream
      val raw = ByteArrayOutputStream().use { output ->
        stream?.use { input ->
          val buffer = ByteArray(8192)
          while (true) {
            checkActive()
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            if (output.size() > MAX_RESPONSE_BYTES) throw IOException("响应超过 1 MiB，可能未访问到预期接口")
          }
        }
        output.toString("UTF-8")
      }
      checkActive()
      val parsed = try { JSONObject(raw) } catch (_: Exception) { null }
      if (status != 200) {
        val hint = if (status == 503 && parsed?.optString("code") == "MODEL_NOT_CONFIGURED")
          "请在服务端配置 PRODUCT_VISION_API_KEY 和 PRODUCT_VISION_BASE_URL"
        else when (status) {
          400 -> "请检查图片和入参格式"
          413 -> "请求体超过网关或服务端大小限制（imageBytes=${image.size}, requestBytes=${payload.size}）；请减小上传图片或提高服务端请求体上限"
          502 -> "模型调用失败或输出不符合约定"
          503 -> "服务或网关暂不可用"
          504 -> "模型调用超时"
          else -> "请检查响应错误信息和访问权限"
        }
        throw ProductScreenshotApiException(status, parsed?.toString(2) ?: raw.take(300), "HTTP $status：$hint")
      }
      val result = parsed ?: throw ProductScreenshotApiException(status, raw.take(300),
        "HTTP $status：响应不是 JSON，请检查登录跳转、网关或访问权限")
      try { validate(result) } catch (error: IllegalArgumentException) {
        throw ProductScreenshotApiException(status, result.toString(2), error.message.orEmpty())
      }
      return ProductScreenshotResponse(result.toString(2), (System.nanoTime() - started) / 1_000_000)
    } finally {
      activeConnection.compareAndSet(connection, null)
      connection.disconnect()
    }
  }

  private fun validate(result: JSONObject) {
    require(result.keys().asSequence().toSet() == setOf("anchor_name", "live_time_desc", "product_list",
      "knowledge_id", "knowledge_type", "knowledge_keyword")) { "响应顶层字段不符合商品截图接口契约" }
    for (key in listOf("anchor_name", "live_time_desc")) require(result.opt(key) is String) { "$key 必须是字符串" }
    val products = result.optJSONArray("product_list") ?: throw IllegalArgumentException("product_list 必须是数组")
    val fields = setOf("product_name", "price_rule", "live_time_desc", "product_image")
    for (i in 0 until products.length()) {
      val product = products.optJSONObject(i) ?: throw IllegalArgumentException("product_list[$i] 必须是对象")
      require(product.keys().asSequence().toSet() == fields) { "product_list[$i] 字段不完整或包含额外字段" }
      for (key in fields) require(product.opt(key) is String) { "product_list[$i].$key 必须是字符串" }
    }
    // The script requires presence of knowledge_* fields but does not constrain their types.
  }

  private fun checkActive() {
    if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("长截图识别已取消")
  }

  companion object {
    const val ENDPOINT = "https://pre-tm-app-turbo.alibaba-inc.com/api/offline/product/analyze"
    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 1024 * 1024
    private const val TIMEOUT_MILLIS = 300_000
  }
}
