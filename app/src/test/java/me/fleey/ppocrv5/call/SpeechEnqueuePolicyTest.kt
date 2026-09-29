package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEnqueuePolicyTest {
  @Test
  fun `failed immediate enqueue reports error and completion`() {
    val callbacks = mutableListOf<String>()
    val utterance = SpeechUtterance(5L, SpeechStage.ACKNOWLEDGEMENT)

    val failed = SpeechEnqueuePolicy().handle(
      resultCode = -1,
      utterance = utterance,
      onError = { callbacks += "error:$it" },
      onFinished = { callbacks += "finished:$it" },
    )

    assertTrue(failed)
    assertEquals(listOf("error:$utterance", "finished:$utterance"), callbacks)
  }

  @Test
  fun `successful enqueue leaves callbacks alone`() {
    var calls = 0

    val failed = SpeechEnqueuePolicy().handle(
      resultCode = 0,
      utterance = SpeechUtterance(5L, SpeechStage.RESULT),
      onError = { calls++ },
      onFinished = { calls++ },
    )

    assertFalse(failed)
    assertEquals(0, calls)
  }
}
