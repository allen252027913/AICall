package me.fleey.ppocrv5.call

enum class LocalCallAction {
  READ_SCREEN,
  RECOGNIZE_PRODUCT,
  JD_CHECK_IN,
  WECHAT_PRODUCT_CAPTURE,
  WECHAT_LONG_SCREENSHOT,
  ACCESSIBILITY_COMMAND,
}

data class IntentMatch(
  val action: LocalCallAction,
  val acknowledgement: String,
)

class LocalCallTurnPolicy(
  private val screenCommandDetector: ScreenCommandDetector = ScreenCommandDetector(),
) {
  fun match(transcript: String): IntentMatch? {
    val normalized = normalize(transcript)
    return when {
      normalized.contains("长截图") ->
        IntentMatch(LocalCallAction.WECHAT_LONG_SCREENSHOT, "好的，开始截取微信当前页面。")
      normalized.contains(WECHAT_CAPTURE_COMMAND) ->
        IntentMatch(LocalCallAction.WECHAT_PRODUCT_CAPTURE, WECHAT_CAPTURE_ACKNOWLEDGEMENT)
      normalized.contains(JD_CHECK_IN_COMMAND) ->
        IntentMatch(LocalCallAction.JD_CHECK_IN, JD_CHECK_IN_ACKNOWLEDGEMENT)
      normalized.contains(PRODUCT_COMMAND) ->
        IntentMatch(LocalCallAction.RECOGNIZE_PRODUCT, PRODUCT_ACKNOWLEDGEMENT)
      screenCommandDetector.shouldCapture(transcript) ->
        IntentMatch(LocalCallAction.READ_SCREEN, SCREEN_ACKNOWLEDGEMENT)
      else -> null
    }
  }

  @Suppress("UNUSED_PARAMETER")
  fun shouldInterrupt(partial: String, spokenText: String): Boolean {
    return normalize(partial) == STOP_COMMAND
  }

  private fun normalize(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)

  private companion object {
    const val STOP_COMMAND = "先停一下"
    const val JD_CHECK_IN_COMMAND = "去京东打卡"
    const val WECHAT_CAPTURE_COMMAND = "微信商品关键帧"
    const val PRODUCT_COMMAND = "帮我看一下这个商品"
    const val JD_CHECK_IN_ACKNOWLEDGEMENT = "好的，我现在帮你去京东打卡。"
    const val WECHAT_CAPTURE_ACKNOWLEDGEMENT = "好的，我现在去微信打开商品并截取关键帧。"
    const val SCREEN_ACKNOWLEDGEMENT = "好的，我现在替你查看当前屏幕。"
    const val PRODUCT_ACKNOWLEDGEMENT = "好的，我现在帮你识别这个商品。"
  }
}
