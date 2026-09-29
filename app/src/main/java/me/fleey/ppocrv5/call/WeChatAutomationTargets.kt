package me.fleey.ppocrv5.call

import java.util.Calendar
import java.util.TimeZone

enum class WeChatTarget(val label: String) {
  TOP_SEARCH("搜索"),
  SUBMIT_SEARCH("搜索"),
  SERVICE_CENTER("所有女生会员服务中心"),
  LIVE_PREVIEW("直播预告"),
  NEXT_DAY("下一天"),
}

object WeChatDateSelectionPolicy {
  fun tomorrowDayOfMonth(
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
  ): Int = Calendar.getInstance(timeZone).apply {
    timeInMillis = nowMillis
    add(Calendar.DAY_OF_MONTH, 1)
  }.get(Calendar.DAY_OF_MONTH)
}

object WeChatQueryEntryStrategy {
  fun <T> enter(
    currentFocusedInput: T?,
    cachedFocusedInput: T?,
    isUsableFocusedInput: (T) -> Boolean,
    enterFromFocusedInput: (T) -> Boolean,
    enterFromWindowTree: () -> Boolean,
  ): Boolean {
    if (
      currentFocusedInput != null &&
      isUsableFocusedInput(currentFocusedInput) &&
      enterFromFocusedInput(currentFocusedInput)
    ) {
      return true
    }
    if (
      cachedFocusedInput != null &&
      cachedFocusedInput !== currentFocusedInput &&
      isUsableFocusedInput(cachedFocusedInput) &&
      enterFromFocusedInput(cachedFocusedInput)
    ) {
      return true
    }
    return enterFromWindowTree()
  }
}

enum class WeChatQueryEntryMethod {
  ALREADY_PRESENT,
  SET_TEXT,
  CLIPBOARD_PASTE,
}

object WeChatVerifiedQueryEntryStrategy {
  fun enter(
    expectedText: String,
    currentText: () -> CharSequence?,
    performSetText: () -> Boolean,
    prepareClipboard: () -> Boolean,
    performPaste: () -> Boolean,
    refresh: () -> Unit,
  ): WeChatQueryEntryMethod? {
    if (currentText().matches(expectedText)) return WeChatQueryEntryMethod.ALREADY_PRESENT

    performSetText()
    refresh()
    if (currentText().matches(expectedText)) return WeChatQueryEntryMethod.SET_TEXT

    if (!prepareClipboard()) return null
    performPaste()
    refresh()
    return WeChatQueryEntryMethod.CLIPBOARD_PASTE.takeIf {
      currentText().matches(expectedText)
    }
  }

  private fun CharSequence?.matches(expectedText: String): Boolean =
    this?.toString() == expectedText
}

object WeChatTextQueryClickStrategy {
  fun <T> click(
    candidates: Iterable<T>,
    matches: (T) -> Boolean,
    performDirectClick: (T) -> Boolean,
    performAncestorClick: (T) -> Boolean,
  ): Boolean {
    candidates.forEach { candidate ->
      if (!matches(candidate)) return@forEach
      if (performDirectClick(candidate) || performAncestorClick(candidate)) return true
    }
    return false
  }
}

object WeChatAccessibilityNodePolicy {
  fun matches(
    text: CharSequence?,
    contentDescription: CharSequence?,
    target: WeChatTarget,
  ): Boolean {
    return matches(text, contentDescription, target.label)
  }

  fun matches(
    text: CharSequence?,
    contentDescription: CharSequence?,
    expectedLabel: String,
  ): Boolean {
    val expected = normalize(expectedLabel)
    return normalize(text?.toString().orEmpty()) == expected ||
      normalize(contentDescription?.toString().orEmpty()) == expected
  }

  fun isQueryInput(editable: Boolean, enabled: Boolean): Boolean = editable && enabled

  private fun normalize(value: String): String =
    value.lowercase().filterNot(Char::isWhitespace)
}

object WeChatImeSearchActionPolicy {
  fun submit(
    sdkInt: Int,
    editable: Boolean,
    enabled: Boolean,
    focused: Boolean,
    performImeEnter: () -> Boolean,
  ): Boolean {
    if (sdkInt < MIN_SUPPORTED_SDK || !editable || !enabled || !focused) return false
    return performImeEnter()
  }

  private const val MIN_SUPPORTED_SDK = 30
}

data class WeChatFeedCandidate(
  val top: Int,
  val bottom: Int,
  val left: Int,
  val right: Int,
  val collectionItem: Boolean,
  val clickable: Boolean,
)

object WeChatFeedNodePolicy {
  fun selectFirst(
    candidates: Iterable<WeChatFeedCandidate>,
    anchorBottom: Int,
    screenHeight: Int,
  ): WeChatFeedCandidate? = candidates
    .filter { candidate ->
      isCandidate(
        top = candidate.top,
        bottom = candidate.bottom,
        anchorBottom = anchorBottom,
        screenHeight = screenHeight,
        collectionItem = candidate.collectionItem,
        clickable = candidate.clickable,
      )
    }
    .minWithOrNull(
      compareBy<WeChatFeedCandidate> { it.top }
        .thenByDescending { it.collectionItem }
        .thenBy { it.left }
        .thenByDescending { it.bottom - it.top },
    )

  fun isCandidate(
    top: Int,
    bottom: Int,
    anchorBottom: Int,
    screenHeight: Int,
    collectionItem: Boolean,
    clickable: Boolean,
  ): Boolean {
    if (!collectionItem && !clickable) return false
    if (top < anchorBottom || bottom <= top) return false
    if (bottom - top < MIN_PRODUCT_HEIGHT_PX) return false
    return top < screenHeight * MAX_TOP_PERCENT / 100
  }

  private const val MIN_PRODUCT_HEIGHT_PX = 80
  private const val MAX_TOP_PERCENT = 90
}
