package me.fleey.ppocrv5.call

fun interface WeChatKeyFrameCaptureProvider {
  fun request(token: Long, callback: (Result<WeChatKeyFrame>) -> Unit)
}

/** In-process bridge from the accessibility service to the active MediaProjection owner. */
object WeChatKeyFrameCaptureBridge {
  @Volatile private var provider: WeChatKeyFrameCaptureProvider? = null

  @Synchronized
  fun install(value: WeChatKeyFrameCaptureProvider) {
    provider = value
  }

  @Synchronized
  fun uninstall(value: WeChatKeyFrameCaptureProvider) {
    if (provider === value) provider = null
  }

  fun request(token: Long, callback: (Result<WeChatKeyFrame>) -> Unit): Boolean {
    val current = provider ?: return false
    current.request(token, callback)
    return true
  }
}
