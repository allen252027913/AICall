package me.fleey.ppocrv5.call

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityCommandLeaseGateTest {
  private val swipe = AccessibilityCommand.Swipe(SwipeDirection.UP)

  @Test
  fun `cancellation that wins before JD transition prevents transition and Toast`() {
    val store = claimedStore()
    val gate = AccessibilityCommandLeaseGate(store::executeIfActive)
    assertTrue(store.cancel(7L))
    var transitions = 0
    var toasts = 0

    assertFalse(
      gate.transitionAndFeedback(
        token = 7L,
        transition = { transitions += 1; true },
        feedback = { toasts += 1 },
      ),
    )

    assertEquals(0, transitions)
    assertEquals(0, toasts)
  }

  @Test
  fun `JD transition and Toast share one cancellation linearization boundary`() {
    val store = claimedStore()
    val gate = AccessibilityCommandLeaseGate(store::executeIfActive)
    val transitionEntered = CountDownLatch(1)
    val allowTransition = CountDownLatch(1)
    val effectFinished = CountDownLatch(1)
    val cancellationFinished = CountDownLatch(1)
    val effects = mutableListOf<String>()
    Thread {
      gate.transitionAndFeedback(
        token = 7L,
        transition = {
          transitionEntered.countDown()
          check(allowTransition.await(2, TimeUnit.SECONDS))
          effects += "transition"
          true
        },
        feedback = { effects += "toast" },
      )
      effectFinished.countDown()
    }.start()

    assertTrue(transitionEntered.await(1, TimeUnit.SECONDS))
    Thread {
      store.cancel(7L)
      cancellationFinished.countDown()
    }.start()

    assertFalse(cancellationFinished.await(250, TimeUnit.MILLISECONDS))
    allowTransition.countDown()
    assertTrue(effectFinished.await(1, TimeUnit.SECONDS))
    assertTrue(cancellationFinished.await(1, TimeUnit.SECONDS))
    assertEquals(listOf("transition", "toast"), effects)
  }

  private fun claimedStore() = TokenizedAccessibilityCommandStore(maxAgeMillis = 1_000L).apply {
    check(reserve(7L))
    check(enqueue(AutomationCommandEnvelope(7L, 100L, swipe)))
    check(claim(200L) is CommandClaim.Ready)
  }
}
