package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JdFallbackGestureCompletionTest {
  @Test
  fun `accepted gesture has no success effect until completion callback`() {
    val effects = mutableListOf<String>()
    val completion = completion(effects = effects)

    assertTrue(completion.onDispatchResult(accepted = true))
    assertTrue(effects.isEmpty())

    completion.onCompleted()
    assertEquals(listOf("completed"), effects)
    completion.onCompleted()
    completion.onCancelled()
    assertEquals(listOf("completed"), effects)
  }

  @Test
  fun `dispatch rejection and cancellation fail exactly once`() {
    val rejectedEffects = mutableListOf<String>()
    val rejected = completion(effects = rejectedEffects)
    assertFalse(rejected.onDispatchResult(accepted = false))
    rejected.onCancelled()
    assertEquals(listOf("failed"), rejectedEffects)

    val cancelledEffects = mutableListOf<String>()
    val cancelled = completion(effects = cancelledEffects)
    assertTrue(cancelled.onDispatchResult(accepted = true))
    cancelled.onCancelled()
    cancelled.onCompleted()
    assertEquals(listOf("failed"), cancelledEffects)
  }

  @Test
  fun `dispatch exception is converted into one truthful failure`() {
    val effects = mutableListOf<String>()
    val completion = completion(effects = effects)

    assertFalse(completion.dispatch { error("binder unavailable") })
    completion.onCancelled()

    assertEquals(listOf("failed"), effects)
  }

  @Test
  fun `late callback after cancellation still invokes local cleanup exactly once`() {
    val effects = mutableListOf<String>()
    val completion = completion(effects = effects)

    assertTrue(completion.onDispatchResult(accepted = true))
    completion.onCompleted()
    completion.onCancelled()

    assertEquals(listOf("completed"), effects)
  }

  private fun completion(
    effects: MutableList<String>,
  ) = JdFallbackGestureCompletion(
    onCompleted = { effects += "completed" },
    onFailed = { effects += "failed" },
  )
}
