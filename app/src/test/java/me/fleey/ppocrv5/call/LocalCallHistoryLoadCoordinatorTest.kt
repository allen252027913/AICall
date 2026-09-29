package me.fleey.ppocrv5.call

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallHistoryLoadCoordinatorTest {
  @Test
  fun `newer load inherits resume evaluation and is the only navigation decision`() {
    val resumeGate = LocalCallResumeEvaluationGate()
    val coordinator = LocalCallHistoryLoadCoordinator(resumeGate)
    val resumeEpoch = resumeGate.beginResume()
    val olderRequest = coordinator.beginLoad(resumeEpoch)
    val releaseOlder = CountDownLatch(1)
    val olderFinished = CountDownLatch(1)
    val decisions = mutableListOf<Int>()
    var navigationState = LocalCallResultNavigationState().markUserLeft(setOf("old"))
    Thread {
      check(releaseOlder.await(2, TimeUnit.SECONDS))
      coordinator.claimLatestCompletion(olderRequest)?.let { completion ->
        if (completion.evaluateResume) {
          val returned = navigationState.onAppResumed(setOf("old"))
          navigationState = returned.nextState
          decisions += returned.unseenExternalCaptures
        }
      }
      olderFinished.countDown()
    }.start()

    val newerRequest = coordinator.beginLoad()
    assertEquals(resumeEpoch, newerRequest.resumeEpoch)
    val newerCompletion = coordinator.claimLatestCompletion(newerRequest)
    assertTrue(requireNotNull(newerCompletion).evaluateResume)
    val returned = navigationState.onAppResumed(setOf("old", "new"))
    navigationState = returned.nextState
    decisions += returned.unseenExternalCaptures

    releaseOlder.countDown()
    assertTrue(olderFinished.await(1, TimeUnit.SECONDS))

    assertEquals(listOf(1), decisions)
    assertFalse(navigationState.awaitingExternalReturn)
    assertNull(coordinator.claimLatestCompletion(olderRequest))
    assertFalse(resumeGate.tryConsume(resumeEpoch))
  }

  @Test
  fun `leave invalidation prevents non resume load from inheriting stale evaluation`() {
    val resumeGate = LocalCallResumeEvaluationGate()
    val coordinator = LocalCallHistoryLoadCoordinator(resumeGate)
    resumeGate.beginResume()

    resumeGate.invalidateForLeaveOrPause()
    val departureLoad = coordinator.beginLoad()

    assertNull(departureLoad.resumeEpoch)
    assertFalse(requireNotNull(coordinator.claimLatestCompletion(departureLoad)).evaluateResume)
  }
}
