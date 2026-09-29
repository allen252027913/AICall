package me.fleey.ppocrv5.call

enum class WeChatAutomationStage {
  OPEN_WECHAT,
  OPEN_TOP_SEARCH,
  ENTER_QUERY,
  SUBMIT_SEARCH,
  OPEN_SERVICE_CENTER,
  OPEN_LIVE_PREVIEW,
  SELECT_NEXT_DAY,
  WAIT_PRODUCT_LIST,
  OPEN_FIRST_PRODUCT,
  WAIT_PRODUCT_DETAIL,
  COMPLETE,
}

enum class WeChatAutomationDecision {
  WAIT_FOR_WECHAT_LAUNCH,
  SEARCH_TOP_SEARCH,
  ENTER_QUERY,
  SUBMIT_IME_SEARCH,
  OPEN_SERVICE_CENTER,
  OPEN_LIVE_PREVIEW,
  SELECT_NEXT_DAY,
  WAIT_FOR_PRODUCT_LIST,
  OPEN_FIRST_PRODUCT,
  WAIT_FOR_PRODUCT_DETAIL,
  COMPLETE,
  FAIL_TIMEOUT,
  IGNORE,
}

data class WeChatAutomationTimingPolicy(
  val stageTimeoutMillis: Long,
  val detailSettleMillis: Long,
  val schedulingMarginMillis: Long = 1_000L,
  val productListSettleMillis: Long = 1_000L,
) {
  init {
    require(stageTimeoutMillis > 0L)
    require(detailSettleMillis >= 0L)
    require(schedulingMarginMillis > 0L)
    require(productListSettleMillis >= 0L)
  }

  val overallTimeoutMillis: Long
    get() = stageTimeoutMillis * ACTIONABLE_STAGE_COUNT + productListSettleMillis +
      detailSettleMillis + schedulingMarginMillis

  fun outerDeadlineFrom(createdAtMillis: Long): Long =
    if (createdAtMillis > Long.MAX_VALUE - overallTimeoutMillis) Long.MAX_VALUE
    else createdAtMillis + overallTimeoutMillis

  fun remainingUntil(deadlineMillis: Long, nowMillis: Long): Long =
    if (nowMillis >= deadlineMillis) 0L else deadlineMillis - nowMillis

  companion object {
    val DEFAULT = WeChatAutomationTimingPolicy(
      stageTimeoutMillis = 20_000L,
      detailSettleMillis = 1_200L,
    )

    private const val ACTIONABLE_STAGE_COUNT = 7L
  }
}

class WeChatAutomationStateMachine(
  val token: Long,
  createdAtMillis: Long,
  val targetDayOfMonth: Int,
  private val timing: WeChatAutomationTimingPolicy = WeChatAutomationTimingPolicy.DEFAULT,
) {
  var stage: WeChatAutomationStage = WeChatAutomationStage.OPEN_WECHAT
    private set

  private var stageStartedAtMillis = createdAtMillis

  init {
    require(token > 0L) { "WeChat automation token must be positive" }
    require(targetDayOfMonth in 1..31) { "targetDayOfMonth must be a calendar day" }
  }

  @Synchronized
  fun next(nowMillis: Long): WeChatAutomationDecision {
    if (stage == WeChatAutomationStage.OPEN_WECHAT) {
      return WeChatAutomationDecision.WAIT_FOR_WECHAT_LAUNCH
    }
    // Accessibility refresh events may run the machine before its delayed callback.
    // Keep the wait here so neither OCR nor node lookup can see the old product list.
    if (stage == WeChatAutomationStage.WAIT_PRODUCT_LIST) {
      if (elapsed(nowMillis) < timing.productListSettleMillis) {
        return WeChatAutomationDecision.WAIT_FOR_PRODUCT_LIST
      }
      transition(
        WeChatAutomationStage.OPEN_FIRST_PRODUCT,
        stageStartedAtMillis + timing.productListSettleMillis,
      )
    }
    if (stage == WeChatAutomationStage.WAIT_PRODUCT_DETAIL) {
      return if (elapsed(nowMillis) >= timing.detailSettleMillis) {
        WeChatAutomationDecision.COMPLETE
      } else {
        WeChatAutomationDecision.WAIT_FOR_PRODUCT_DETAIL
      }
    }
    if (stage == WeChatAutomationStage.COMPLETE) return WeChatAutomationDecision.IGNORE
    if (elapsed(nowMillis) >= timing.stageTimeoutMillis) {
      return WeChatAutomationDecision.FAIL_TIMEOUT
    }
    return when (stage) {
      WeChatAutomationStage.OPEN_TOP_SEARCH -> WeChatAutomationDecision.SEARCH_TOP_SEARCH
      WeChatAutomationStage.ENTER_QUERY -> WeChatAutomationDecision.ENTER_QUERY
      WeChatAutomationStage.SUBMIT_SEARCH -> WeChatAutomationDecision.SUBMIT_IME_SEARCH
      WeChatAutomationStage.OPEN_SERVICE_CENTER -> WeChatAutomationDecision.OPEN_SERVICE_CENTER
      WeChatAutomationStage.OPEN_LIVE_PREVIEW -> WeChatAutomationDecision.OPEN_LIVE_PREVIEW
      WeChatAutomationStage.SELECT_NEXT_DAY -> WeChatAutomationDecision.SELECT_NEXT_DAY
      WeChatAutomationStage.OPEN_FIRST_PRODUCT -> WeChatAutomationDecision.OPEN_FIRST_PRODUCT
      else -> WeChatAutomationDecision.IGNORE
    }
  }

  @Synchronized
  fun onWeChatLaunched(token: Long, nowMillis: Long): Boolean =
    transitionIf(token, WeChatAutomationStage.OPEN_WECHAT, WeChatAutomationStage.OPEN_TOP_SEARCH, nowMillis)

  @Synchronized
  fun onQueryEntered(token: Long, nowMillis: Long): Boolean =
    transitionIf(token, WeChatAutomationStage.ENTER_QUERY, WeChatAutomationStage.SUBMIT_SEARCH, nowMillis)

  @Synchronized
  fun onTargetClicked(token: Long, target: WeChatTarget, nowMillis: Long): Boolean {
    if (token != this.token || elapsed(nowMillis) >= timing.stageTimeoutMillis) return false
    val nextStage = when (stage to target) {
      WeChatAutomationStage.OPEN_TOP_SEARCH to WeChatTarget.TOP_SEARCH ->
        WeChatAutomationStage.ENTER_QUERY
      WeChatAutomationStage.SUBMIT_SEARCH to WeChatTarget.SUBMIT_SEARCH ->
        WeChatAutomationStage.OPEN_SERVICE_CENTER
      WeChatAutomationStage.OPEN_SERVICE_CENTER to WeChatTarget.SERVICE_CENTER ->
        WeChatAutomationStage.OPEN_LIVE_PREVIEW
      WeChatAutomationStage.OPEN_LIVE_PREVIEW to WeChatTarget.LIVE_PREVIEW ->
        WeChatAutomationStage.SELECT_NEXT_DAY
      WeChatAutomationStage.SELECT_NEXT_DAY to WeChatTarget.NEXT_DAY ->
        WeChatAutomationStage.WAIT_PRODUCT_LIST
      else -> return false
    }
    transition(nextStage, nowMillis)
    return true
  }

  @Synchronized
  fun onFirstProductClicked(token: Long, nowMillis: Long): Boolean =
    transitionIf(
      token,
      WeChatAutomationStage.OPEN_FIRST_PRODUCT,
      WeChatAutomationStage.WAIT_PRODUCT_DETAIL,
      nowMillis,
    )

  @Synchronized
  fun finish(token: Long): Boolean {
    if (token != this.token || stage == WeChatAutomationStage.COMPLETE) return false
    stage = WeChatAutomationStage.COMPLETE
    return true
  }

  private fun transitionIf(
    token: Long,
    expected: WeChatAutomationStage,
    next: WeChatAutomationStage,
    nowMillis: Long,
  ): Boolean {
    if (token != this.token || stage != expected) return false
    transition(next, nowMillis)
    return true
  }

  private fun transition(next: WeChatAutomationStage, nowMillis: Long) {
    stage = next
    stageStartedAtMillis = nowMillis
  }

  private fun elapsed(nowMillis: Long): Long = (nowMillis - stageStartedAtMillis).coerceAtLeast(0L)
}
