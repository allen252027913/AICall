package me.fleey.ppocrv5.call

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallJdAutomationControllerTest {
  @Test
  fun `disabled accessibility is rejected without launching or enqueueing`() {
    val launches = mutableListOf<Unit>()
    val commands = mutableListOf<AutomationCommandEnvelope>()
    val controller = controller(
      accessibilityEnabled = false,
      launch = { launches += Unit; JdAppLaunchResult.LAUNCHED },
      enqueue = { commands += it; true },
    )

    assertEquals(
      JdAutomationStartOutcome.Rejected(JdAutomationResult.ACCESSIBILITY_DISABLED),
      controller.start(token = 11L),
    )
    assertTrue(launches.isEmpty())
    assertTrue(commands.isEmpty())
  }

  @Test
  fun `missing JD and launch failure return concrete outcomes without enqueueing`() {
    val commands = mutableListOf<AutomationCommandEnvelope>()
    val missing = controller(
      launch = { JdAppLaunchResult.NOT_INSTALLED },
      enqueue = { commands += it; true },
    )
    val failed = controller(
      launch = { JdAppLaunchResult.LAUNCH_FAILED },
      enqueue = { commands += it; true },
    )

    assertEquals(
      JdAutomationStartOutcome.Rejected(JdAutomationResult.JD_NOT_INSTALLED),
      missing.start(11L),
    )
    assertEquals(
      JdAutomationStartOutcome.Rejected(JdAutomationResult.LAUNCH_FAILED),
      failed.start(12L),
    )
    assertTrue(commands.isEmpty())
  }

  @Test
  fun `successful launch enqueues one tokenized JD command ready to find My`() {
    val commands = mutableListOf<AutomationCommandEnvelope>()
    val controller = controller(enqueue = { commands += it; true })

    assertTrue(controller.start(11L) is JdAutomationStartOutcome.Started)
    assertEquals(1, commands.size)
    assertEquals(11L, commands.single().token)
    val command = commands.single().command as AccessibilityCommand.JdCheckIn
    assertEquals(JdAutomationStage.OPEN_MY, command.machine.stage)
    assertEquals(JdAutomationDecision.SEARCH_MY, command.machine.next(2_000L))
  }

  @Test
  fun `busy command slot is rejected after a successful launch`() {
    val controller = controller(enqueue = { false })

    assertEquals(
      JdAutomationStartOutcome.Rejected(JdAutomationResult.BUSY),
      controller.start(11L),
    )
  }

  @Test
  fun `slow launch does not consume the machine outer timeout budget`() {
    val timing = JdAutomationTimingPolicy.DEFAULT
    var now = 1_000L
    val commands = mutableListOf<AutomationCommandEnvelope>()
    val controller = controller(
      launch = {
        now = 6_000L
        JdAppLaunchResult.LAUNCHED
      },
      enqueue = { commands += it; true },
      elapsedRealtime = { now },
    )

    val outcome = controller.start(11L) as JdAutomationStartOutcome.Started

    assertEquals(6_000L, outcome.machineCreatedAtMillis)
    assertEquals(6_000L + timing.overallTimeoutMillis, outcome.outerDeadlineMillis)
    assertEquals(timing.overallTimeoutMillis, timing.remainingUntil(outcome.outerDeadlineMillis, now))
    val finalLegalFallbackAt = outcome.machineCreatedAtMillis +
      timing.myStageTimeoutMillis - 1L +
      timing.moreStageTimeoutMillis + timing.fallbackCallbackBudgetMillis +
      timing.claimAllStageTimeoutMillis + timing.fallbackCallbackBudgetMillis
    assertTrue(finalLegalFallbackAt < outcome.outerDeadlineMillis)
    assertEquals(6_000L, commands.single().issuedAtMillis)
  }

  @Test
  fun `cancellation before atomic launch gate prevents stale JD launch and enqueue`() {
    val store = TokenizedAccessibilityCommandStore(maxAgeMillis = 5_000L)
    assertTrue(store.reserve(11L))
    val launchGateEntered = CountDownLatch(1)
    val allowLaunchGate = CountDownLatch(1)
    val completed = CountDownLatch(1)
    val outcome = AtomicReference<JdAutomationStartOutcome>()
    var launches = 0
    val controller = controller(
      launch = { launches += 1; JdAppLaunchResult.LAUNCHED },
      enqueue = store::enqueue,
      executeIfActive = { token, action ->
        launchGateEntered.countDown()
        check(allowLaunchGate.await(2, TimeUnit.SECONDS))
        store.executeIfActive(token, action)
      },
    )
    Thread {
      outcome.set(controller.start(11L))
      completed.countDown()
    }.start()

    assertTrue(launchGateEntered.await(1, TimeUnit.SECONDS))
    assertTrue(store.cancel(11L))
    allowLaunchGate.countDown()
    assertTrue(completed.await(1, TimeUnit.SECONDS))

    assertEquals(JdAutomationStartOutcome.Rejected(JdAutomationResult.INTERRUPTED), outcome.get())
    assertEquals(0, launches)
    assertFalse(store.isActive(11L))
    assertEquals(null, store.claim(nowMillis = 3_000L))
  }

  private fun controller(
    accessibilityEnabled: Boolean = true,
    launch: () -> JdAppLaunchResult = { JdAppLaunchResult.LAUNCHED },
    enqueue: (AutomationCommandEnvelope) -> Boolean,
    elapsedRealtime: () -> Long = { 2_000L },
    executeIfActive: (Long, () -> Unit) -> Boolean = { _, action -> action(); true },
  ) = LocalCallJdAutomationController(
    accessibilityEnabled = { accessibilityEnabled },
    launcher = JdApplicationLauncher(launch),
    enqueue = enqueue,
    elapsedRealtime = elapsedRealtime,
    executeIfActive = executeIfActive,
  )
}
