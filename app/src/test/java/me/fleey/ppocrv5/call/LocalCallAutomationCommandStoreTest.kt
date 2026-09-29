package me.fleey.ppocrv5.call

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallAutomationCommandStoreTest {
  private val store = TokenizedAccessibilityCommandStore(maxAgeMillis = 1_000L)
  private val swipe = AccessibilityCommand.Swipe(SwipeDirection.UP)

  @Test
  fun `exactly one fresh command can be claimed for execution`() {
    assertTrue(store.reserve(7L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe)))
    assertFalse(store.reserve(8L))
    assertFalse(store.enqueue(AutomationCommandEnvelope(8L, 101L, swipe)))

    assertEquals(
      CommandClaim.Ready(AutomationCommandEnvelope(7L, 100L, swipe)),
      store.claim(nowMillis = 200L),
    )
    assertNull(store.claim(nowMillis = 201L))
  }

  @Test
  fun `expired command is rejected instead of executed`() {
    assertTrue(store.reserve(7L))
    store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe))

    assertEquals(CommandClaim.Stale(token = 7L), store.claim(nowMillis = 1_101L))
    assertNull(store.claim(nowMillis = 1_102L))
  }

  @Test
  fun `accepted tokens cannot be replayed even after the slot is clear`() {
    assertTrue(store.reserve(7L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe)))
    store.claim(nowMillis = 200L)
    assertTrue(store.cancel(7L))

    assertFalse(store.reserve(7L))
    assertFalse(store.reserve(6L))
    assertTrue(store.reserve(8L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(8L, 300L, swipe)))
  }

  @Test
  fun `cancel before reservation leaves a tombstone and next token progresses`() {
    assertTrue(store.cancel(7L))

    assertFalse(store.reserve(7L))
    assertFalse(store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe)))
    var staleSideEffects = 0
    assertFalse(store.executeIfActive(7L) { staleSideEffects += 1 })
    assertEquals(0, staleSideEffects)

    assertTrue(store.reserve(8L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(8L, 200L, swipe)))
    assertEquals(
      CommandClaim.Ready(AutomationCommandEnvelope(8L, 200L, swipe)),
      store.claim(nowMillis = 201L),
    )
  }

  @Test
  fun `cancel before worker subscription prevents late registration and enqueue`() {
    val resultBus = TokenizedAccessibilityResultBus()
    assertTrue(store.reserve(7L))
    val workerReady = CountDownLatch(1)
    val releaseWorker = CountDownLatch(1)
    val workerDone = CountDownLatch(1)
    var subscribed = false
    var enqueued = false
    Thread {
      workerReady.countDown()
      check(releaseWorker.await(2, TimeUnit.SECONDS))
      subscribed = store.executeIfActive(7L) {
        check(resultBus.subscribe(7L) { error("cancelled result must not deliver") })
      }
      enqueued = store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe))
      workerDone.countDown()
    }.start()

    assertTrue(workerReady.await(1, TimeUnit.SECONDS))
    assertTrue(store.cancel(7L))
    resultBus.unsubscribe(7L)
    releaseWorker.countDown()
    assertTrue(workerDone.await(1, TimeUnit.SECONDS))

    assertFalse(subscribed)
    assertFalse(enqueued)
    assertNull(store.claim(nowMillis = 200L))
    assertTrue(store.reserve(8L))
  }

  @Test
  fun `cancel after timeout install but before enqueue leaves no claimable command`() {
    val resultBus = TokenizedAccessibilityResultBus()
    assertTrue(store.reserve(7L))
    assertTrue(store.executeIfActive(7L) { check(resultBus.subscribe(7L) { }) })
    val timeoutInstalled = CountDownLatch(1)
    val releaseEnqueue = CountDownLatch(1)
    val workerDone = CountDownLatch(1)
    var enqueued = true
    Thread {
      timeoutInstalled.countDown()
      check(releaseEnqueue.await(2, TimeUnit.SECONDS))
      enqueued = store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe))
      workerDone.countDown()
    }.start()

    assertTrue(timeoutInstalled.await(1, TimeUnit.SECONDS))
    assertTrue(store.cancel(7L))
    assertTrue(resultBus.unsubscribe(7L))
    releaseEnqueue.countDown()
    assertTrue(workerDone.await(1, TimeUnit.SECONDS))

    assertFalse(enqueued)
    assertNull(store.claim(nowMillis = 200L))
    assertTrue(store.reserve(8L))
  }

  @Test
  fun `claimed command remains cancellable before its side effect`() {
    assertTrue(store.reserve(7L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe)))
    assertEquals(
      CommandClaim.Ready(AutomationCommandEnvelope(7L, 100L, swipe)),
      store.claim(nowMillis = 200L),
    )

    assertTrue(store.cancel(7L))
    var sideEffects = 0
    assertFalse(store.runIfActive(7L) { sideEffects += 1; true })
    assertEquals(0, sideEffects)
  }

  @Test
  fun `cancellation immediately before click or gesture prevents both effects`() {
    assertTrue(store.reserve(7L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe)))
    store.claim(nowMillis = 200L)

    assertTrue(store.cancel(7L))
    var nodeClicks = 0
    var gestureDispatches = 0
    assertFalse(store.runIfActive(7L) { nodeClicks += 1; true })
    assertFalse(store.runIfActive(7L) { gestureDispatches += 1; true })
    assertEquals(0, nodeClicks)
    assertEquals(0, gestureDispatches)
  }

  @Test
  fun `gesture watchdog releases claimed slot and late callback cannot affect next command`() {
    val effects = mutableListOf<String>()
    assertTrue(store.reserve(7L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe)))
    store.claim(nowMillis = 200L)
    val completion = AccessibilityGestureCompletion(
      onCompleted = { effects += "old-completed" },
      onFailed = {
        store.cancel(7L)
        effects += "old-timeout"
      },
    )

    assertTrue(completion.onDispatchResult(accepted = true))
    completion.onTimedOut()
    assertEquals(listOf("old-timeout"), effects)

    assertTrue(store.reserve(8L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(8L, 300L, swipe)))
    assertEquals(
      CommandClaim.Ready(AutomationCommandEnvelope(8L, 300L, swipe)),
      store.claim(nowMillis = 301L),
    )
    completion.onCompleted()
    completion.onCancelled()
    assertEquals(listOf("old-timeout"), effects)
    assertTrue(store.isActive(8L))
  }

  @Test
  fun `one newer command can wait behind a claimed gesture`() {
    assertTrue(store.reserve(7L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe)))
    store.claim(nowMillis = 200L)

    assertTrue(store.reserve(8L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(8L, 201L, swipe)))
    assertFalse(store.reserve(9L))
    assertTrue(store.completeClaim(7L))

    assertEquals(
      CommandClaim.Ready(AutomationCommandEnvelope(8L, 201L, swipe)),
      store.claim(nowMillis = 203L),
    )
  }

  @Test
  fun `side effect that linearizes first blocks cancellation until it returns`() {
    assertTrue(store.reserve(7L))
    assertTrue(store.enqueue(AutomationCommandEnvelope(7L, 100L, swipe)))
    store.claim(nowMillis = 200L)
    val actionEntered = CountDownLatch(1)
    val releaseAction = CountDownLatch(1)
    val actionFinished = CountDownLatch(1)
    val cancellationFinished = CountDownLatch(1)
    var effects = 0

    Thread {
      store.runIfActive(7L) {
        actionEntered.countDown()
        check(releaseAction.await(2, TimeUnit.SECONDS))
        effects += 1
        true
      }
      actionFinished.countDown()
    }.start()
    assertTrue(actionEntered.await(1, TimeUnit.SECONDS))
    Thread {
      store.cancel(7L)
      cancellationFinished.countDown()
    }.start()

    assertFalse(cancellationFinished.await(250, TimeUnit.MILLISECONDS))
    releaseAction.countDown()
    assertTrue(actionFinished.await(1, TimeUnit.SECONDS))
    assertTrue(cancellationFinished.await(1, TimeUnit.SECONDS))
    assertEquals(1, effects)
    assertFalse(store.isActive(7L))
  }

  @Test
  fun `recreated service receives a token beyond prior sessions accepted by store and result bus`() {
    val firstService = LocalCallAutomationTokenProvider()
    val resultBus = TokenizedAccessibilityResultBus()
    val priorTokens = (1..3).map { firstService.nextToken() }
    priorTokens.forEachIndexed { index, token ->
      assertTrue(store.reserve(token))
      assertTrue(store.enqueue(AutomationCommandEnvelope(token, 100L + index, swipe)))
      assertEquals(
        CommandClaim.Ready(AutomationCommandEnvelope(token, 100L + index, swipe)),
        store.claim(nowMillis = 200L + index),
      )
      assertTrue(store.cancel(token))
      assertTrue(resultBus.subscribe(token) { })
      assertTrue(resultBus.publish(AccessibilityCommandResult(token, true, "done")))
    }

    val recreatedService = LocalCallAutomationTokenProvider()
    val laterToken = recreatedService.nextToken()

    assertTrue(laterToken > priorTokens.max())
    assertTrue(store.reserve(laterToken))
    assertTrue(store.enqueue(AutomationCommandEnvelope(laterToken, 300L, swipe)))
    assertTrue(resultBus.subscribe(laterToken) { })
  }
}
