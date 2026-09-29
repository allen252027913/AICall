package me.fleey.ppocrv5.call

import java.util.Base64

/** A conservative client budget, not a discovered limit of the remote gateway. */
object ProductScreenshotPayload {
  const val MAX_REQUEST_BYTES = 900 * 1024
  private const val PREFIX = "{\"imageBase64\":\""
  private const val SUFFIX = "\"}"
  private const val JSON_BYTES = PREFIX.length + SUFFIX.length
  const val MAX_ENCODED_IMAGE_BYTES = ((MAX_REQUEST_BYTES - JSON_BYTES) / 4) * 3

  fun requestBytes(imageBytes: Int): Long {
    require(imageBytes >= 0)
    return JSON_BYTES + 4L * ((imageBytes.toLong() + 2) / 3)
  }

  fun encode(image: ByteArray): ByteArray {
    val size = requestBytes(image.size)
    require(size <= MAX_REQUEST_BYTES) { "请求体 $size bytes 超过客户端 $MAX_REQUEST_BYTES bytes 预算，请先压缩图片" }
    // Base64's ASCII alphabet requires no JSON escaping; use the same representation on JVM/Android.
    return (PREFIX + Base64.getEncoder().encodeToString(image) + SUFFIX).toByteArray(Charsets.UTF_8)
  }
}
