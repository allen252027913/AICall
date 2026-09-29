package me.fleey.ppocrv5.call

data class WeChatKeyboardTapPlan(
  val queryInput: WeChatScreenPoint,
  val pinyinKeys: List<WeChatScreenPoint>,
  val firstCandidate: WeChatScreenPoint,
  val keyboardSearch: WeChatScreenPoint,
)

/**
 * Coordinate fallback for WeChat builds that deliberately expose an empty accessibility tree.
 * Ratios are based on the visible search field and the standard Huawei Pinyin QWERTY keyboard.
 */
object WeChatKeyboardTapPolicy {
  fun planFor(screenWidth: Int, screenHeight: Int): WeChatKeyboardTapPlan? {
    if (screenWidth <= 0 || screenHeight <= 0) return null
    val width = screenWidth.toFloat()
    val height = screenHeight.toFloat()
    return WeChatKeyboardTapPlan(
      queryInput = WeChatScreenPoint(width * 0.5f, width * 0.18f),
      pinyinKeys = listOf(
        point(width, height, 0.885f, 0.7963f), // l
        point(width, height, 0.745f, 0.7333f), // i
        point(width, height, 0.685f, 0.7963f), // j
        point(width, height, 0.085f, 0.7963f), // a
        point(width, height, 0.05f, 0.7333f), // q
        point(width, height, 0.745f, 0.7333f), // i
      ),
      firstCandidate = point(width, height, 0.115f, 0.6667f),
      keyboardSearch = point(width, height, 0.91f, 0.9333f),
    )
  }

  private fun point(
    width: Float,
    height: Float,
    xRatio: Float,
    yRatio: Float,
  ) = WeChatScreenPoint(width * xRatio, height * yRatio)
}
