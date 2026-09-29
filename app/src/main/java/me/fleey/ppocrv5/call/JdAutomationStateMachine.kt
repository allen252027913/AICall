package me.fleey.ppocrv5.call

enum class JdAutomationStage {
  OPEN_JD,
  OPEN_MY,
  CLICK_MORE,
  CLICK_CLAIM_ALL,
  COMPLETE,
}

enum class JdAutomationDecision {
  WAIT_FOR_JD_LAUNCH,
  SEARCH_MY,
  SEARCH_MORE,
  SEARCH_CLAIM_ALL,
  TAP_MORE_FALLBACK,
  TAP_CLAIM_ALL_FALLBACK,
  FAIL_TIMEOUT,
  IGNORE,
}

data class JdAutomationTimingPolicy(
  val myStageTimeoutMillis: Long,
  val moreStageTimeoutMillis: Long,
  val claimAllStageTimeoutMillis: Long,
  val fallbackCallbackBudgetMillis: Long,
  val pageTransitionMillis: Long,
  val schedulingMarginMillis: Long,
) {
  init {
    require(myStageTimeoutMillis > 0L)
    require(moreStageTimeoutMillis > 0L)
    require(claimAllStageTimeoutMillis > 0L)
    require(fallbackCallbackBudgetMillis > 0L)
    require(pageTransitionMillis >= 0L)
    require(schedulingMarginMillis > 0L)
  }

  val overallTimeoutMillis: Long
    get() = myStageTimeoutMillis + moreStageTimeoutMillis + claimAllStageTimeoutMillis +
      fallbackCallbackBudgetMillis * FALLBACK_STAGE_COUNT +
      pageTransitionMillis * PAGE_TRANSITION_COUNT +
      schedulingMarginMillis

  fun outerDeadlineFrom(machineCreatedAtMillis: Long): Long =
    if (machineCreatedAtMillis > Long.MAX_VALUE - overallTimeoutMillis) Long.MAX_VALUE
    else machineCreatedAtMillis + overallTimeoutMillis

  fun remainingUntil(outerDeadlineMillis: Long, nowMillis: Long): Long =
    if (nowMillis >= outerDeadlineMillis) 0L else outerDeadlineMillis - nowMillis

  companion object {
    val DEFAULT = JdAutomationTimingPolicy(
      myStageTimeoutMillis = 8_000L,
      moreStageTimeoutMillis = 5_000L,
      claimAllStageTimeoutMillis = 5_000L,
      fallbackCallbackBudgetMillis = 2_000L,
      pageTransitionMillis = 500L,
      schedulingMarginMillis = 1_000L,
    )

    private const val FALLBACK_STAGE_COUNT = 2L
    private const val PAGE_TRANSITION_COUNT = 2L
  }
}

class JdAutomationStateMachine(
  val token: Long,
  createdAtMillis: Long,
  private val timing: JdAutomationTimingPolicy = JdAutomationTimingPolicy.DEFAULT,
) {
  var stage: JdAutomationStage = JdAutomationStage.OPEN_JD
    private set

  private var stageStartedAtMillis = createdAtMillis

  init {
    require(token > 0L) { "JD automation token must be positive" }
  }

  @Synchronized
  fun next(nowMillis: Long): JdAutomationDecision = when (stage) {
    JdAutomationStage.OPEN_JD -> JdAutomationDecision.WAIT_FOR_JD_LAUNCH
    JdAutomationStage.OPEN_MY -> if (elapsed(nowMillis) >= timing.myStageTimeoutMillis) {
      JdAutomationDecision.FAIL_TIMEOUT
    } else {
      JdAutomationDecision.SEARCH_MY
    }
    JdAutomationStage.CLICK_MORE -> if (elapsed(nowMillis) >= timing.moreStageTimeoutMillis) {
      JdAutomationDecision.TAP_MORE_FALLBACK
    } else {
      JdAutomationDecision.SEARCH_MORE
    }
    JdAutomationStage.CLICK_CLAIM_ALL -> if (
      elapsed(nowMillis) >= timing.claimAllStageTimeoutMillis
    ) {
      JdAutomationDecision.TAP_CLAIM_ALL_FALLBACK
    } else {
      JdAutomationDecision.SEARCH_CLAIM_ALL
    }
    JdAutomationStage.COMPLETE -> JdAutomationDecision.IGNORE
  }

  @Synchronized
  fun onJdLaunched(token: Long, nowMillis: Long): Boolean {
    if (token != this.token || stage != JdAutomationStage.OPEN_JD) return false
    transition(JdAutomationStage.OPEN_MY, nowMillis)
    return true
  }

  @Synchronized
  fun onTargetClicked(token: Long, target: JdTarget, nowMillis: Long): Boolean {
    if (token != this.token) return false
    val nextStage = when (stage to target) {
      JdAutomationStage.OPEN_MY to JdTarget.MY -> JdAutomationStage.CLICK_MORE
      JdAutomationStage.CLICK_MORE to JdTarget.MORE -> JdAutomationStage.CLICK_CLAIM_ALL
      JdAutomationStage.CLICK_CLAIM_ALL to JdTarget.CLAIM_ALL -> JdAutomationStage.COMPLETE
      else -> return false
    }
    transition(nextStage, nowMillis)
    return true
  }

  @Synchronized
  fun finish(token: Long): Boolean {
    if (token != this.token || stage == JdAutomationStage.COMPLETE) return false
    stage = JdAutomationStage.COMPLETE
    return true
  }

  private fun transition(nextStage: JdAutomationStage, nowMillis: Long) {
    stage = nextStage
    stageStartedAtMillis = nowMillis
  }

  private fun elapsed(nowMillis: Long): Long =
    (nowMillis - stageStartedAtMillis).coerceAtLeast(0L)
}
