package me.fleey.ppocrv5.call

data class WeChatKeyFrame(
  val width: Int,
  val height: Int,
  val blocks: List<ScreenTextBlock>,
) {
  init {
    require(width > 0 && height > 0)
  }
}

sealed interface WeChatTopSearchFrameAction {
  data class SelectChatsTab(val point: WeChatScreenPoint) : WeChatTopSearchFrameAction
  data class OpenSearch(val point: WeChatScreenPoint) : WeChatTopSearchFrameAction
  data object SearchAlreadyOpen : WeChatTopSearchFrameAction
}

sealed interface WeChatFrameTargetAction {
  data class ClickTarget(val point: WeChatScreenPoint) : WeChatFrameTargetAction
  data class ClickIntermediate(val point: WeChatScreenPoint) : WeChatFrameTargetAction
}

object WeChatVisualTargetLocator {
  fun topSearchAction(frame: WeChatKeyFrame): WeChatTopSearchFrameAction? {
    if (queryInput(frame) != null) return WeChatTopSearchFrameAction.SearchAlreadyOpen
    val chatTab = frame.blocks
      .filter { normalize(it.text) == "微信" && it.centerY >= frame.height * 0.8f }
      .maxByOrNull(ScreenTextBlock::centerY)
    val chatTitleVisible = frame.blocks.any {
      val text = normalize(it.text)
      it.centerY < frame.height * 0.2f && (text == "微信" || text.startsWith("微信("))
    }
    if (!chatTitleVisible) {
      return chatTab?.let {
        WeChatTopSearchFrameAction.SelectChatsTab(it.toPoint())
      }
    }
    val capturedSearchIcon = frame.blocks
      .filter { block ->
        val text = normalize(block.text).lowercase()
        block.centerX in frame.width * 0.72f..frame.width * 0.9f &&
          block.centerY < frame.height * 0.12f &&
          text in SEARCH_ICON_OCR_LABELS
      }
      .minByOrNull { block -> kotlin.math.abs(block.centerX - frame.width * 0.83f) }
      ?.toPoint()
    return (capturedSearchIcon ?: WeChatTopSearchTapPolicy.pointFor(frame.width, frame.height))
      ?.let(WeChatTopSearchFrameAction::OpenSearch)
  }

  fun queryInput(frame: WeChatKeyFrame): WeChatScreenPoint? = frame.blocks
    .firstOrNull { normalize(it.text).contains(QUERY_PLACEHOLDER) }
    ?.toPoint()

  fun labeledTarget(frame: WeChatKeyFrame, target: WeChatTarget): WeChatScreenPoint? {
    val expected = normalize(target.label)
    return frame.blocks
      .filter { block ->
        val actual = normalize(block.text)
        actual == expected || actual.contains(expected)
      }
      .minByOrNull(ScreenTextBlock::centerY)
      ?.toPoint()
  }

  fun targetAction(frame: WeChatKeyFrame, target: WeChatTarget): WeChatFrameTargetAction? {
    if (target == WeChatTarget.LIVE_PREVIEW) {
      return livePreviewTab(frame)?.let(WeChatFrameTargetAction::ClickTarget)
    }
    labeledTarget(frame, target)?.let { return WeChatFrameTargetAction.ClickTarget(it) }
    if (target != WeChatTarget.SERVICE_CENTER) return null
    return frame.blocks
      .filter { block ->
        normalize(block.text) == QUERY &&
          block.centerY in frame.height * 0.12f..frame.height * 0.55f
      }
      .minByOrNull(ScreenTextBlock::centerY)
      ?.toPoint()
      ?.let(WeChatFrameTargetAction::ClickIntermediate)
  }

  private fun livePreviewTab(frame: WeChatKeyFrame): WeChatScreenPoint? {
    val tabs = frame.blocks.filter {
      it.centerY in frame.height * 0.90f..frame.height * 0.98f
    }
    // Tap the icon above the label, away from the system navigation edge.
    val iconOffset = frame.height * 0.025f
    tabs.firstOrNull { normalize(it.text) == WeChatTarget.LIVE_PREVIEW.label }?.let {
      return WeChatScreenPoint(it.centerX, it.centerY - iconOffset)
    }
    // Grey tab text can be omitted by OCR. Infer the fourth slot only when
    // the second and third slots of this mini-app's five-tab bar are present.
    val trial = tabs.firstOrNull {
      normalize(it.text) == "积分试用" && it.centerX in frame.width * 0.2f..frame.width * 0.4f
    } ?: return null
    val shop = tabs.firstOrNull {
      normalize(it.text) == "会员商城" && it.centerX in frame.width * 0.4f..frame.width * 0.6f
    } ?: return null
    if (kotlin.math.abs(trial.centerY - shop.centerY) > frame.height * 0.01f) return null
    val spacing = shop.centerX - trial.centerX
    if (spacing !in frame.width * 0.15f..frame.width * 0.25f) return null
    val x = shop.centerX + spacing
    if (x !in frame.width * 0.6f..frame.width * 0.8f) return null
    return WeChatScreenPoint(x, (trial.centerY + shop.centerY) / 2f - iconOffset)
  }

  fun nextDayAction(
    frame: WeChatKeyFrame,
    targetDayOfMonth: Int,
  ): WeChatFrameTargetAction? {
    require(targetDayOfMonth in 1..31)
    val dateRow = dateRow(frame)
    dateRow.firstOrNull { dateDayOfMonth(it.text) == targetDayOfMonth }
      ?.let { return WeChatFrameTargetAction.ClickTarget(it.toPoint()) }
    if (dateRow.size < MIN_DATE_ROW_BLOCKS) return null
    val rowCenterY = dateRow.map(ScreenTextBlock::centerY).sorted()[dateRow.size / 2]
    return WeChatFrameTargetAction.ClickIntermediate(
      WeChatScreenPoint(frame.width * NEXT_PAGE_X_RATIO, rowCenterY),
    )
  }

  fun pinyinKeys(frame: WeChatKeyFrame): List<WeChatScreenPoint>? {
    val keyboardBlocks = frame.blocks.filter { it.centerY >= frame.height * KEYBOARD_TOP_RATIO }
    fun key(label: String): WeChatScreenPoint? = keyboardBlocks
      .filter { normalize(it.text).lowercase() == label }
      .minByOrNull(ScreenTextBlock::centerY)
      ?.toPoint()
    val l = key("l") ?: return null
    val i = key("i") ?: return null
    val j = key("j") ?: return null
    val a = key("a") ?: return null
    val q = key("q") ?: return null
    return listOf(l, i, j, a, q, i)
  }

  fun queryCandidate(frame: WeChatKeyFrame): WeChatScreenPoint? = frame.blocks
    .filter { block ->
      normalize(block.text) == QUERY && block.centerY >= frame.height * CANDIDATE_TOP_RATIO
    }
    .minByOrNull(ScreenTextBlock::centerY)
    ?.toPoint()

  fun keyboardSearch(frame: WeChatKeyFrame): WeChatScreenPoint? = frame.blocks
    .filter { block ->
      normalize(block.text) == WeChatTarget.SUBMIT_SEARCH.label &&
        block.centerY >= frame.height * KEYBOARD_TOP_RATIO
    }
    .maxByOrNull(ScreenTextBlock::centerX)
    ?.toPoint()

  fun hasKeyboard(frame: WeChatKeyFrame): Boolean = frame.blocks.count { block ->
    block.centerY >= frame.height * KEYBOARD_TOP_RATIO
  } >= MIN_KEYBOARD_BLOCKS

  fun firstProduct(frame: WeChatKeyFrame): WeChatScreenPoint? {
    val allProducts = frame.blocks.firstOrNull {
      normalize(it.text) == "全部商品" &&
        it.centerY in frame.height * 0.2f..frame.height * 0.65f
    } ?: return null
    val sortRow = frame.blocks.filter {
      it.centerY > allProducts.centerY &&
        it.centerY < allProducts.centerY + frame.height * 0.08f
    }
    val sortRowY = sortRow.firstOrNull { normalize(it.text).startsWith("默认") }?.centerY
      ?: run {
        // The small default/price/new labels can all be absent from OCR.
        // Require both larger controls to identify the same sort row safely.
        val rank = sortRow.firstOrNull { normalize(it.text).contains("今日预约榜") }
          ?: return null
        val filter = sortRow.firstOrNull { normalize(it.text).contains("场次筛选") }
          ?: return null
        if (kotlin.math.abs(rank.centerY - filter.centerY) > frame.height * 0.01f) return null
        (rank.centerY + filter.centerY) / 2f
      }
    // In this list layout the first card's title is on the right, just below
    // the sort row. Never use the live-room header above the product tabs.
    return frame.blocks
      .filter { block ->
        val text = normalize(block.text)
        block.centerX in frame.width * 0.45f..frame.width * 0.96f &&
          block.centerY in (sortRowY + frame.height * 0.025f)..
            (sortRowY + frame.height * 0.14f) &&
          text.length >= 4 && text.any(Char::isLetter) &&
          PRODUCT_PLACEHOLDERS.none(text::contains)
      }
      .minByOrNull(ScreenTextBlock::centerY)
      ?.toPoint()
  }

  private fun dateRow(frame: WeChatKeyFrame): List<ScreenTextBlock> = frame.blocks.filter { block ->
    block.centerY in frame.height * DATE_ROW_TOP_RATIO..frame.height * DATE_ROW_BOTTOM_RATIO &&
      DATE_NUMBER.containsMatchIn(block.text)
  }

  private fun dateDayOfMonth(text: CharSequence): Int? = DATE_NUMBER
    .find(text)
    ?.groupValues
    ?.get(1)
    ?.toIntOrNull()
    ?.takeIf { it in 1..31 }

  private fun ScreenTextBlock.toPoint() = WeChatScreenPoint(centerX, centerY)

  private fun normalize(value: CharSequence?): String = value
    ?.toString()
    .orEmpty()
    .filterNot(Char::isWhitespace)

  private const val QUERY_PLACEHOLDER = "搜索本地或网络结果"
  private const val QUERY = "李佳琦"
  private val PRODUCT_PLACEHOLDERS = listOf("加载", "暂无", "没有商品", "上播提醒", "场次筛选")
  private const val KEYBOARD_TOP_RATIO = 0.65f
  private const val CANDIDATE_TOP_RATIO = 0.55f
  private const val MIN_KEYBOARD_BLOCKS = 5
  private const val DATE_ROW_TOP_RATIO = 0.1f
  private const val DATE_ROW_BOTTOM_RATIO = 0.17f
  private const val MIN_DATE_ROW_BLOCKS = 4
  private const val NEXT_PAGE_X_RATIO = 0.94f
  private val DATE_NUMBER = Regex("(\\d{1,2})")
  private val SEARCH_ICON_OCR_LABELS = setOf("q", "o", "0")
}
