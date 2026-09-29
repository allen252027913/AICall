package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallServicePoliciesTest {
  @Test
  fun `projection stop invalidates a long screenshot before it can be saved`() {
    assertTrue(LocalCallServicePolicies().shouldInvalidateForProjectionStop(
      LocalCallTurnIdentity(1L, 2L, LocalCallAction.WECHAT_LONG_SCREENSHOT),
    ))
  }
  @Test
  fun `projection stop invalidates an active wechat capture flow`() {
    assertTrue(
      LocalCallServicePolicies().shouldInvalidateForProjectionStop(
        LocalCallTurnIdentity(1L, 2L, LocalCallAction.WECHAT_PRODUCT_CAPTURE),
      ),
    )
  }

  @Test
  fun `wechat capture may commit only for the matching active shared turn`() {
    val candidate = LocalCallTurnIdentity(1L, 2L, LocalCallAction.WECHAT_PRODUCT_CAPTURE)
    val policies = LocalCallServicePolicies()

    assertTrue(policies.canCommitScreen(candidate, candidate, callActive = true, screenSharing = true))
    assertFalse(policies.canCommitScreen(candidate, candidate, callActive = true, screenSharing = false))
  }
  private val readTurn = LocalCallTurnIdentity(3L, 7L, LocalCallAction.READ_SCREEN)
  private val productTurn = LocalCallTurnIdentity(3L, 8L, LocalCallAction.RECOGNIZE_PRODUCT)
  private val policy = LocalCallServicePolicies()

  @Test
  fun `projection stop interrupts active screen dependent turns`() {
    assertTrue(policy.shouldInvalidateForProjectionStop(readTurn))
    assertTrue(policy.shouldInvalidateForProjectionStop(productTurn))
    assertFalse(policy.shouldInvalidateForProjectionStop(null))
  }

  @Test
  fun `screen commit rejects end revoke and stale turn`() {
    assertTrue(policy.canCommitScreen(readTurn, readTurn, callActive = true, screenSharing = true))
    assertFalse(policy.canCommitScreen(readTurn, readTurn, callActive = false, screenSharing = true))
    assertFalse(policy.canCommitScreen(readTurn, readTurn, callActive = true, screenSharing = false))
    assertFalse(policy.canCommitScreen(readTurn, productTurn, callActive = true, screenSharing = true))
  }

  @Test
  fun `stale tts errors cannot publish into current turn`() {
    assertFalse(policy.canPublishTtsError(readTurn, productTurn, callActive = true))
    assertFalse(policy.canPublishTtsError(readTurn, readTurn, callActive = false))
    assertTrue(policy.canPublishTtsError(readTurn, readTurn, callActive = true))
  }

  @Test
  fun `non blocking teardown serializes close after accepted work`() {
    val events = mutableListOf<String>()
    val queued = mutableListOf<() -> Unit>()
    val owner = LocalCallWorkerOwner(
      submitToWorker = { task -> events += "submitted"; queued += task },
      shutdownWorker = { events += "shutdown" },
    )

    assertTrue(owner.submit { events += "work" })
    owner.shutdownWithoutWaiting { events += "close" }

    assertFalse(owner.submit { events += "late" })
    assertEquals(listOf("submitted", "submitted", "shutdown"), events)

    queued.forEach { it() }
    assertEquals(listOf("submitted", "submitted", "shutdown", "work", "close"), events)
  }
}
