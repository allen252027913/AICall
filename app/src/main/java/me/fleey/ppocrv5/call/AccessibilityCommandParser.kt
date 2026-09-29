package me.fleey.ppocrv5.call

sealed interface AccessibilityCommand {
  data class Swipe(
    val direction: SwipeDirection,
    val durationMillis: Long = 360L,
    val requiredPackage: String? = null,
    val verticalTravelFraction: Float = .56f,
  ) : AccessibilityCommand {
    init {
      require(durationMillis in 1L..1000L)
      require(verticalTravelFraction in .1f.. .8f)
    }
  }
  data class ClickListItem(val index: Int) : AccessibilityCommand {
    init {
      require(index >= 1) { "List item index must be one based" }
    }
  }
  data class ClickText(val text: String) : AccessibilityCommand {
    init {
      require(text.isNotBlank()) { "Click text must not be blank" }
    }
  }
  data class JdCheckIn(val machine: JdAutomationStateMachine) : AccessibilityCommand
  data class WeChatProductCapture(val machine: WeChatAutomationStateMachine) : AccessibilityCommand
}

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

class AccessibilityCommandParser {
  fun parse(transcript: String): AccessibilityCommand? {
    val compact = transcript.trim().filterNot(Char::isWhitespace)
    if (compact.isBlank()) return null

    swipeDirection(compact)?.let { return AccessibilityCommand.Swipe(it) }

    val ordinalMatch = ORDINAL.find(compact)
    if (ordinalMatch != null) {
      val index = parsePositiveNumber(ordinalMatch.groupValues[1]) ?: return null
      return AccessibilityCommand.ClickListItem(index)
    }
    if (INVALID_ORDINAL_HINT.containsMatchIn(compact)) return null

    val target = CLICK_TEXT.matchEntire(compact)
      ?.groupValues
      ?.get(1)
      ?.trim('，', ',', '。', '.', '！', '!', '？', '?')
      ?.removeSuffix("一下")
      ?.removeSuffix("吧")
      ?.trim()
      ?.takeIf { it.isNotBlank() && it !in GENERIC_CLICK_TARGETS }
      ?: return null
    return AccessibilityCommand.ClickText(target)
  }

  private fun swipeDirection(compact: String): SwipeDirection? = when {
    compact.contains("下一页") || UP_SWIPE.containsMatchIn(compact) -> SwipeDirection.UP
    compact.contains("上一页") || DOWN_SWIPE.containsMatchIn(compact) -> SwipeDirection.DOWN
    LEFT_SWIPE.containsMatchIn(compact) -> SwipeDirection.LEFT
    RIGHT_SWIPE.containsMatchIn(compact) -> SwipeDirection.RIGHT
    else -> null
  }

  private fun parsePositiveNumber(value: String): Int? {
    value.toIntOrNull()?.let { return it.takeIf { number -> number >= 1 } }
    if (value == "零") return null
    var total = 0
    var current = 0
    value.forEach { character ->
      when (character) {
        '十' -> {
          total += (if (current == 0) 1 else current) * 10
          current = 0
        }
        '百' -> {
          total += (if (current == 0) 1 else current) * 100
          current = 0
        }
        else -> current = CHINESE_DIGITS[character] ?: return null
      }
    }
    return (total + current).takeIf { it >= 1 }
  }

  private companion object {
    val UP_SWIPE = Regex("(?:向|往)?上滑(?:动|一下)?")
    val DOWN_SWIPE = Regex("(?:向|往)?下滑(?:动|一下)?")
    val LEFT_SWIPE = Regex("(?:向|往)?左滑(?:动|一下)?")
    val RIGHT_SWIPE = Regex("(?:向|往)?右滑(?:动|一下)?")
    val ORDINAL = Regex("第([0-9零一二三四五六七八九十百两]+)(?:个|项|条)(?:列表项|项目|选项)?")
    val INVALID_ORDINAL_HINT = Regex("第[^，。！？,.!?]*(?:个|项|条)")
    val CLICK_TEXT = Regex("^(?:请|麻烦)?(?:帮我)?(?:点击|点一下|点下|点开|点)(?:屏幕上的?)?(.+)$")
    val GENERIC_CLICK_TARGETS = setOf("列表项", "项目", "选项", "按钮", "屏幕")
    val CHINESE_DIGITS = mapOf(
      '零' to 0,
      '一' to 1,
      '二' to 2,
      '两' to 2,
      '三' to 3,
      '四' to 4,
      '五' to 5,
      '六' to 6,
      '七' to 7,
      '八' to 8,
      '九' to 9,
    )
  }
}
