package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallSpeakerInputRoutingTest {
  @Test
  fun `preference defaults off and persists selected value`() {
    var persisted: Boolean? = null
    val preference = LocalCallSpeakerInputPreference(
      readPersisted = { persisted },
      writePersisted = { persisted = it },
    )

    assertFalse(preference.isEnabled())
    preference.setEnabled(true)
    assertTrue(preference.isEnabled())
    assertEquals(LocalCallAudioInputMode.INCLUDE_SPEAKER_PLAYBACK, preference.inputMode())
  }

  @Test
  fun `idle update persists without starting or stopping recorder`() {
    var persisted: Boolean? = null
    val events = mutableListOf<String>()
    val controller = LocalCallSpeechInputController(
      preference = LocalCallSpeakerInputPreference({ persisted }, { persisted = it }),
      stopListening = { events += "stop" },
      startListening = { events += "start:$it" },
    )

    controller.updateSpeakerPlaybackInput(enabled = true, callActive = false)

    assertTrue(persisted == true)
    assertTrue(events.isEmpty())
    assertEquals(
      SpeakerInputAction.PERSIST_LOCALLY,
      LocalCallSpeakerInputActionPolicy.action(callActive = false),
    )
  }

  @Test
  fun `active update restarts recorder exactly once in persisted mode`() {
    var persisted: Boolean? = null
    val events = mutableListOf<String>()
    val controller = LocalCallSpeechInputController(
      preference = LocalCallSpeakerInputPreference({ persisted }, { persisted = it }),
      stopListening = { events += "stop" },
      startListening = { events += "start:$it" },
    )

    controller.updateSpeakerPlaybackInput(enabled = true, callActive = true)

    assertEquals(
      listOf("stop", "start:${LocalCallAudioInputMode.INCLUDE_SPEAKER_PLAYBACK}"),
      events,
    )
    assertEquals(
      SpeakerInputAction.RESTART_ACTIVE_RECORDER,
      LocalCallSpeakerInputActionPolicy.action(callActive = true),
    )
  }
}
