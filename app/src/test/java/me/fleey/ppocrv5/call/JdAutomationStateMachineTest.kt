package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JdAutomationStateMachineTest {
  @Test
  fun `launch and successful clicks follow the complete state sequence`() {
    val machine = JdAutomationStateMachine(token = 7L, createdAtMillis = 1_000L)

    assertEquals(JdAutomationStage.OPEN_JD, machine.stage)
    assertTrue(machine.onJdLaunched(token = 7L, nowMillis = 1_100L))
    assertEquals(JdAutomationDecision.SEARCH_MY, machine.next(nowMillis = 1_100L))

    assertTrue(machine.onTargetClicked(7L, JdTarget.MY, nowMillis = 1_500L))
    assertEquals(JdAutomationDecision.SEARCH_MORE, machine.next(nowMillis = 1_500L))

    assertTrue(machine.onTargetClicked(7L, JdTarget.MORE, nowMillis = 1_900L))
    assertEquals(JdAutomationDecision.SEARCH_CLAIM_ALL, machine.next(nowMillis = 1_900L))

    assertTrue(machine.onTargetClicked(7L, JdTarget.CLAIM_ALL, nowMillis = 2_200L))
    assertEquals(JdAutomationStage.COMPLETE, machine.stage)
    assertEquals(JdAutomationDecision.IGNORE, machine.next(nowMillis = Long.MAX_VALUE))
  }

  @Test
  fun `each stage uses its locked timeout behavior`() {
    val my = launchedMachine(stageStartedAt = 1_000L)
    assertEquals(JdAutomationDecision.SEARCH_MY, my.next(8_999L))
    assertEquals(JdAutomationDecision.FAIL_TIMEOUT, my.next(9_000L))

    val more = launchedMachine(stageStartedAt = 1_000L).apply {
      onTargetClicked(7L, JdTarget.MY, nowMillis = 2_000L)
    }
    assertEquals(JdAutomationDecision.SEARCH_MORE, more.next(6_999L))
    assertEquals(JdAutomationDecision.TAP_MORE_FALLBACK, more.next(7_000L))

    val claim = launchedMachine(stageStartedAt = 1_000L).apply {
      onTargetClicked(7L, JdTarget.MY, nowMillis = 2_000L)
      onTargetClicked(7L, JdTarget.MORE, nowMillis = 3_000L)
    }
    assertEquals(JdAutomationDecision.SEARCH_CLAIM_ALL, claim.next(7_999L))
    assertEquals(JdAutomationDecision.TAP_CLAIM_ALL_FALLBACK, claim.next(8_000L))
  }

  @Test
  fun `stale token and wrong target cannot advance another command`() {
    val machine = launchedMachine(stageStartedAt = 1_000L)

    assertFalse(machine.onTargetClicked(6L, JdTarget.MY, nowMillis = 1_100L))
    assertFalse(machine.onTargetClicked(7L, JdTarget.MORE, nowMillis = 1_100L))
    assertEquals(JdAutomationStage.OPEN_MY, machine.stage)

    assertTrue(machine.onTargetClicked(7L, JdTarget.MY, nowMillis = 1_200L))
    assertFalse(machine.finish(token = 6L))
    assertEquals(JdAutomationStage.CLICK_MORE, machine.stage)
  }

  @Test
  fun `outer deadline leaves time for both fallbacks after near deadline stages`() {
    val timing = JdAutomationTimingPolicy.DEFAULT
    val machine = JdAutomationStateMachine(
      token = 7L,
      createdAtMillis = 0L,
      timing = timing,
    )
    machine.onJdLaunched(token = 7L, nowMillis = 0L)

    val myClickAt = timing.myStageTimeoutMillis - 1L
    assertTrue(machine.onTargetClicked(7L, JdTarget.MY, myClickAt))
    val moreFallbackAt = myClickAt + timing.moreStageTimeoutMillis
    assertEquals(JdAutomationDecision.TAP_MORE_FALLBACK, machine.next(moreFallbackAt))
    val moreCompletedAt = moreFallbackAt + timing.fallbackCallbackBudgetMillis
    assertTrue(machine.onTargetClicked(7L, JdTarget.MORE, moreCompletedAt))
    val claimFallbackAt = moreCompletedAt + timing.claimAllStageTimeoutMillis
    assertEquals(JdAutomationDecision.TAP_CLAIM_ALL_FALLBACK, machine.next(claimFallbackAt))
    val finalCompletedAt = claimFallbackAt + timing.fallbackCallbackBudgetMillis

    assertTrue(finalCompletedAt < timing.overallTimeoutMillis)
    assertTrue(machine.onTargetClicked(7L, JdTarget.CLAIM_ALL, finalCompletedAt))
    assertEquals(JdAutomationStage.COMPLETE, machine.stage)
  }

  private fun launchedMachine(stageStartedAt: Long): JdAutomationStateMachine =
    JdAutomationStateMachine(token = 7L, createdAtMillis = stageStartedAt).apply {
      onJdLaunched(token = 7L, nowMillis = stageStartedAt)
    }
}
