package me.fleey.ppocrv5.call

data class WeChatScreenPoint(val x: Float, val y: Float)

object WeChatTopSearchTapPolicy {
  fun pointFor(screenWidth: Int, screenHeight: Int): WeChatScreenPoint? {
    if (screenWidth <= 0 || screenHeight <= 0) return null
    return WeChatScreenPoint(
      x = screenWidth * SEARCH_CENTER_X_RATIO,
      y = screenWidth * SEARCH_CENTER_Y_TO_WIDTH_RATIO,
    )
  }

  private const val SEARCH_CENTER_X_RATIO = 0.83f
  private const val SEARCH_CENTER_Y_TO_WIDTH_RATIO = 0.1557f
}

enum class WeChatTopSearchClickMethod {
  ACCESSIBILITY_NODE,
  POSITION_GESTURE,
}

object WeChatTopSearchClickStrategy {
  fun execute(
    clickAccessibleNode: () -> Boolean,
    dispatchPositionTap: () -> Boolean,
  ): WeChatTopSearchClickMethod? = when {
    dispatchPositionTap() -> WeChatTopSearchClickMethod.POSITION_GESTURE
    clickAccessibleNode() -> WeChatTopSearchClickMethod.ACCESSIBILITY_NODE
    else -> null
  }
}
