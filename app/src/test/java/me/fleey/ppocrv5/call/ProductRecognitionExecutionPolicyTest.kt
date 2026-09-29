package me.fleey.ppocrv5.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRecognitionExecutionPolicyTest {
  private val productTurn = LocalCallTurnIdentity(3L, 8L, LocalCallAction.RECOGNIZE_PRODUCT)
  private val readTurn = LocalCallTurnIdentity(3L, 9L, LocalCallAction.READ_SCREEN)
  private val policy = LocalCallServicePolicies()

  @Test
  fun `product work continues only for current turn with an active projection`() {
    assertTrue(policy.canContinueProductRecognition(productTurn, productTurn, true, true))
    assertFalse(policy.canContinueProductRecognition(productTurn, productTurn, false, true))
    assertFalse(policy.canContinueProductRecognition(productTurn, productTurn, true, false))
    assertFalse(policy.canContinueProductRecognition(productTurn, readTurn, true, true))
    assertFalse(policy.canContinueProductRecognition(readTurn, readTurn, true, true))
  }

  @Test
  fun `projection revocation invalidates both capture dependent turn types`() {
    assertTrue(policy.shouldInvalidateForProjectionStop(productTurn))
    assertTrue(policy.shouldInvalidateForProjectionStop(readTurn))
  }
}
