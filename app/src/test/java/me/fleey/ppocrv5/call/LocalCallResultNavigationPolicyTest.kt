package me.fleey.ppocrv5.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallResultNavigationPolicyTest {
  private val policy = LocalCallResultNavigationPolicy()

  @Test
  fun `opens results when returning to the app with unseen external captures`() {
    assertTrue(policy.shouldOpenResults(returnedToApp = true, unseenExternalCaptures = 1))
  }

  @Test
  fun `stays on call when returning to the app without unseen external captures`() {
    assertFalse(policy.shouldOpenResults(returnedToApp = true, unseenExternalCaptures = 0))
  }

  @Test
  fun `does not open results before the app has returned`() {
    assertFalse(policy.shouldOpenResults(returnedToApp = false, unseenExternalCaptures = 1))
  }

  @Test
  fun `only a user departure can make a later resume external`() {
    val resumingInApp = LocalCallResultNavigationState().onAppResumed(setOf("capture-2"))

    assertFalse(resumingInApp.returnedFromUserDeparture)
    assertEquals(0, resumingInApp.unseenExternalCaptures)
  }

  @Test
  fun `departure baseline detects only captures made while away`() {
    val departed = LocalCallResultNavigationState()
      .markUserLeft(setOf("capture-1", "capture-2"))

    val returned = departed.onAppResumed(setOf("capture-1", "capture-2", "capture-3"))

    assertTrue(returned.returnedFromUserDeparture)
    assertEquals(1, returned.unseenExternalCaptures)
    assertFalse(returned.nextState.awaitingExternalReturn)
  }

  @Test
  fun `persisted departure state survives recreation before external return`() {
    val persisted = LocalCallResultNavigationState()
      .markUserLeft(setOf("capture-1"), departureCutoffMillis = 20L)
      .toPersistedValue()

    val recreated = LocalCallResultNavigationState.fromPersistedValue(persisted)
    assertEquals(20L, recreated.departureCutoffMillis)

    val restored = recreated
      .onAppResumed(setOf("capture-1", "capture-2"))

    assertTrue(restored.returnedFromUserDeparture)
    assertEquals(1, restored.unseenExternalCaptures)
    assertEquals(null, restored.nextState.departureCutoffMillis)
  }

  @Test
  fun `recreation before baseline refinement can recover exact departure ids from cutoff`() {
    val persistedBeforeRefinement = LocalCallResultNavigationState()
      .markUserLeft(setOf("current-capture"), departureCutoffMillis = 30L)
      .toPersistedValue()
    val recreated = LocalCallResultNavigationState.fromPersistedValue(persistedBeforeRefinement)
    val allEntries = listOf(
      CaptureHistoryEntry("preloaded-1", 10L, "10_1.jpg", "first", "app"),
      CaptureHistoryEntry("preloaded-2", 20L, "20_2.jpg", "second", "app"),
      CaptureHistoryEntry("external", 31L, "31_3.jpg", "external", "app"),
    )
    val refined = recreated.markUserLeft(
      LocalCallDepartureBaselinePolicy.captureIds(
        entries = allEntries,
        departureCutoffMillis = requireNotNull(recreated.departureCutoffMillis),
        alreadyKnownIds = recreated.departureCaptureIds,
      ),
    )

    val returned = refined.onAppResumed(allEntries.mapTo(mutableSetOf(), CaptureHistoryEntry::id))

    assertEquals(1, returned.unseenExternalCaptures)
    assertEquals(setOf("preloaded-1", "preloaded-2", "current-capture"), refined.departureCaptureIds)
  }

  @Test
  fun `late resume callback cannot consume a newer departure marker`() {
    val gate = LocalCallResumeEvaluationGate()
    val staleResume = gate.beginResume()

    gate.invalidateForLeaveOrPause()

    assertFalse(gate.tryConsume(staleResume))
    val actualReturn = gate.beginResume()
    assertTrue(gate.tryConsume(actualReturn))
    assertFalse(gate.tryConsume(actualReturn))
  }

  @Test
  fun `departure baseline includes all pre departure captures and excludes later ones`() {
    val entries = listOf(
      CaptureHistoryEntry("first", 10L, "10_1.jpg", "first", "app"),
      CaptureHistoryEntry("second", 20L, "20_2.jpg", "second", "app"),
      CaptureHistoryEntry("external", 31L, "31_3.jpg", "external", "app"),
    )

    val baseline = LocalCallDepartureBaselinePolicy.captureIds(
      entries = entries,
      departureCutoffMillis = 30L,
      alreadyKnownIds = setOf("in-memory"),
    )

    assertEquals(setOf("first", "second", "in-memory"), baseline)
  }
}
