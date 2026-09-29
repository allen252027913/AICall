package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatAutomationStateMachineTest {
  @Test
  fun `happy path advances through search mini program live preview date and first product`() {
    val timing = WeChatAutomationTimingPolicy(stageTimeoutMillis = 5_000L, detailSettleMillis = 800L)
    val machine = WeChatAutomationStateMachine(
      7L,
      createdAtMillis = 1_000L,
      targetDayOfMonth = 12,
      timing = timing,
    )

    assertEquals(WeChatAutomationDecision.WAIT_FOR_WECHAT_LAUNCH, machine.next(1_000L))
    assertTrue(machine.onWeChatLaunched(7L, 1_100L))
    assertEquals(WeChatAutomationDecision.SEARCH_TOP_SEARCH, machine.next(1_100L))
    assertTrue(machine.onTargetClicked(7L, WeChatTarget.TOP_SEARCH, 1_200L))
    assertEquals(WeChatAutomationDecision.ENTER_QUERY, machine.next(1_200L))
    assertTrue(machine.onQueryEntered(7L, 1_300L))
    assertEquals(WeChatAutomationDecision.SUBMIT_IME_SEARCH, machine.next(1_300L))
    assertTrue(machine.onTargetClicked(7L, WeChatTarget.SUBMIT_SEARCH, 1_400L))
    assertEquals(WeChatAutomationDecision.OPEN_SERVICE_CENTER, machine.next(1_400L))
    assertTrue(machine.onTargetClicked(7L, WeChatTarget.SERVICE_CENTER, 1_500L))
    assertEquals(WeChatAutomationDecision.OPEN_LIVE_PREVIEW, machine.next(1_500L))
    assertTrue(machine.onTargetClicked(7L, WeChatTarget.LIVE_PREVIEW, 1_600L))
    assertEquals(WeChatAutomationDecision.SELECT_NEXT_DAY, machine.next(1_600L))
    assertTrue(machine.onTargetClicked(7L, WeChatTarget.NEXT_DAY, 1_700L))
    assertEquals(WeChatAutomationDecision.OPEN_FIRST_PRODUCT, machine.next(2_700L))
    assertTrue(machine.onFirstProductClicked(7L, 2_800L))
    assertEquals(WeChatAutomationDecision.WAIT_FOR_PRODUCT_DETAIL, machine.next(3_599L))
    assertEquals(WeChatAutomationDecision.COMPLETE, machine.next(3_600L))
  }

  @Test
  fun `date refresh events cannot request a product frame before one second`() {
    val machine = machineAfterDateClick()
    listOf(1_700L, 1_701L, 2_000L, 2_699L).forEach { now ->
      assertEquals(WeChatAutomationDecision.WAIT_FOR_PRODUCT_LIST, machine.next(now))
      assertFalse(machine.onFirstProductClicked(7L, now))
    }
    assertEquals(WeChatAutomationDecision.OPEN_FIRST_PRODUCT, machine.next(2_700L))
    assertTrue(machine.onFirstProductClicked(7L, 2_700L))
  }

  @Test
  fun `cancelling during date refresh cannot resume product detection`() {
    val machine = machineAfterDateClick()
    assertTrue(machine.finish(7L))
    assertEquals(WeChatAutomationDecision.IGNORE, machine.next(2_700L))
    assertFalse(machine.onFirstProductClicked(7L, 2_700L))
  }

  @Test
  fun `product timeout begins after refresh wait and late polling does not extend it`() {
    val machine = machineAfterDateClick()
    assertEquals(WeChatAutomationDecision.OPEN_FIRST_PRODUCT, machine.next(22_699L))
    assertEquals(WeChatAutomationDecision.FAIL_TIMEOUT, machine.next(22_700L))
    assertEquals(
      WeChatAutomationDecision.FAIL_TIMEOUT,
      machineAfterDateClick().next(22_700L),
    )
  }

  private fun machineAfterDateClick() =
    WeChatAutomationStateMachine(7L, 1_000L, targetDayOfMonth = 12).apply {
      check(onWeChatLaunched(7L, 1_100L))
      check(onTargetClicked(7L, WeChatTarget.TOP_SEARCH, 1_200L))
      check(onQueryEntered(7L, 1_300L))
      check(onTargetClicked(7L, WeChatTarget.SUBMIT_SEARCH, 1_400L))
      check(onTargetClicked(7L, WeChatTarget.SERVICE_CENTER, 1_500L))
      check(onTargetClicked(7L, WeChatTarget.LIVE_PREVIEW, 1_600L))
      check(onTargetClicked(7L, WeChatTarget.NEXT_DAY, 1_700L))
    }

  @Test
  fun `wrong token and out of order events cannot advance the flow`() {
    val machine = WeChatAutomationStateMachine(7L, 1_000L, targetDayOfMonth = 12)
    assertFalse(machine.onWeChatLaunched(8L, 1_100L))
    assertTrue(machine.onWeChatLaunched(7L, 1_100L))
    assertFalse(machine.onTargetClicked(7L, WeChatTarget.SERVICE_CENTER, 1_200L))
    assertFalse(machine.onQueryEntered(7L, 1_200L))
    assertEquals(WeChatAutomationStage.OPEN_TOP_SEARCH, machine.stage)
  }

  @Test
  fun `query entry and ime submission wait for the top search click`() {
    val machine = WeChatAutomationStateMachine(7L, 1_000L, targetDayOfMonth = 12)
    assertTrue(machine.onWeChatLaunched(7L, 1_100L))

    assertEquals(WeChatAutomationDecision.SEARCH_TOP_SEARCH, machine.next(1_100L))
    assertFalse(machine.onQueryEntered(7L, 1_200L))
    assertFalse(machine.onTargetClicked(7L, WeChatTarget.SUBMIT_SEARCH, 1_300L))
    assertEquals(WeChatAutomationStage.OPEN_TOP_SEARCH, machine.stage)

    assertTrue(machine.onTargetClicked(7L, WeChatTarget.TOP_SEARCH, 1_400L))
    assertEquals(WeChatAutomationDecision.ENTER_QUERY, machine.next(1_400L))
    assertTrue(machine.onQueryEntered(7L, 1_500L))
    assertEquals(WeChatAutomationDecision.SUBMIT_IME_SEARCH, machine.next(1_500L))
  }

  @Test
  fun `each actionable stage times out instead of clicking stale pages`() {
    val timing = WeChatAutomationTimingPolicy(stageTimeoutMillis = 2_000L, detailSettleMillis = 800L)
    val machine = WeChatAutomationStateMachine(
      7L,
      1_000L,
      targetDayOfMonth = 12,
      timing = timing,
    )
    machine.onWeChatLaunched(7L, 1_100L)

    assertEquals(WeChatAutomationDecision.SEARCH_TOP_SEARCH, machine.next(3_099L))
    assertEquals(WeChatAutomationDecision.FAIL_TIMEOUT, machine.next(3_100L))
  }

  @Test
  fun `top search click completion cannot advance after its stage deadline`() {
    val timing = WeChatAutomationTimingPolicy(stageTimeoutMillis = 2_000L, detailSettleMillis = 800L)
    val machine = WeChatAutomationStateMachine(
      7L,
      1_000L,
      targetDayOfMonth = 12,
      timing = timing,
    )
    assertTrue(machine.onWeChatLaunched(7L, 1_100L))

    assertFalse(machine.onTargetClicked(7L, WeChatTarget.TOP_SEARCH, 3_100L))
    assertEquals(WeChatAutomationStage.OPEN_TOP_SEARCH, machine.stage)
    assertEquals(WeChatAutomationDecision.FAIL_TIMEOUT, machine.next(3_100L))
  }

  @Test
  fun `default stage budget accommodates repeated OCR keyframes`() {
    val machine = WeChatAutomationStateMachine(7L, 1_000L, targetDayOfMonth = 12)
    assertTrue(machine.onWeChatLaunched(7L, 1_100L))

    assertEquals(WeChatAutomationDecision.SEARCH_TOP_SEARCH, machine.next(16_100L))
  }
}
