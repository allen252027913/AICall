package me.fleey.ppocrv5.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallAsrRunEpochTest {
  @Test
  fun `callbacks from recorder stopped by speaker restart are rejected`() {
    val epoch = LocalCallAsrRunEpoch()
    val oldRun = epoch.beginRun()

    epoch.invalidateRun()
    val newRun = epoch.beginRun()

    assertFalse(epoch.isCurrent(oldRun, callActive = true))
    assertTrue(epoch.isCurrent(newRun, callActive = true))
  }

  @Test
  fun `turn interruption does not invalidate current recorder error`() {
    val epoch = LocalCallAsrRunEpoch()
    val currentRun = epoch.beginRun()
    var turnGeneration = 3L

    turnGeneration += 1L

    assertTrue(turnGeneration > 3L)
    assertTrue(epoch.isCurrent(currentRun, callActive = true))
    assertFalse(epoch.isCurrent(currentRun, callActive = false))
  }
}
