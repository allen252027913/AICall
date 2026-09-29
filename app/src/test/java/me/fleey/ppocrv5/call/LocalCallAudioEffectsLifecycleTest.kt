package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalCallAudioEffectsLifecycleTest {
  @Test
  fun `release all closes active effects before worker cleanup`() {
    val lifecycle = LocalCallAudioEffectsLifecycle()
    val effects = CountingCloseable()
    lifecycle.retain(effects)

    lifecycle.releaseAll()
    lifecycle.release(effects)

    assertEquals(1, effects.closeCount)
  }

  @Test
  fun `replacing active effects closes the previous effects only`() {
    val lifecycle = LocalCallAudioEffectsLifecycle()
    val first = CountingCloseable()
    val second = CountingCloseable()
    lifecycle.retain(first)

    lifecycle.retain(second)

    assertEquals(1, first.closeCount)
    assertEquals(0, second.closeCount)
  }

  private class CountingCloseable : AutoCloseable {
    var closeCount = 0

    override fun close() {
      closeCount += 1
    }
  }
}
