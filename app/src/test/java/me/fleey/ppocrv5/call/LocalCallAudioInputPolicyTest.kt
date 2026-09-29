package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalCallAudioInputPolicyTest {
  @Test
  fun `speaker playback mode leaves microphone preprocessing disabled`() {
    assertEquals(
      LocalCallAudioInputConfiguration(
        preferVoiceCommunicationSource = false,
        enableEchoCancellation = false,
        enableNoiseSuppression = false,
      ),
      LocalCallAudioInputPolicy.resolve(
        mode = LocalCallAudioInputMode.INCLUDE_SPEAKER_PLAYBACK,
        echoCancelerAvailable = true,
      ),
    )
  }

  @Test
  fun `user voice mode enables echo cancellation when the device supports it`() {
    assertEquals(
      LocalCallAudioInputConfiguration(
        preferVoiceCommunicationSource = true,
        enableEchoCancellation = true,
        enableNoiseSuppression = true,
      ),
      LocalCallAudioInputPolicy.resolve(
        mode = LocalCallAudioInputMode.USER_VOICE_ONLY,
        echoCancelerAvailable = true,
      ),
    )
  }

  @Test
  fun `user voice mode keeps noise suppression when echo cancellation is unavailable`() {
    assertEquals(
      LocalCallAudioInputConfiguration(
        preferVoiceCommunicationSource = false,
        enableEchoCancellation = false,
        enableNoiseSuppression = true,
      ),
      LocalCallAudioInputPolicy.resolve(
        mode = LocalCallAudioInputMode.USER_VOICE_ONLY,
        echoCancelerAvailable = false,
      ),
    )
  }
}
