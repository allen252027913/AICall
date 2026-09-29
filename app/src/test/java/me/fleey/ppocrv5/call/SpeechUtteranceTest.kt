package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechUtteranceTest {
  @Test
  fun `encoded acknowledgement carries its query token to the callback`() {
    val utterance = SpeechUtterance(42L, SpeechStage.ACKNOWLEDGEMENT)

    assertEquals(utterance, SpeechUtterance.decode(utterance.encode()))
  }

  @Test
  fun `invalid callback identifier is discarded`() {
    assertNull(SpeechUtterance.decode("local-call:RESULT:0"))
  }
}
