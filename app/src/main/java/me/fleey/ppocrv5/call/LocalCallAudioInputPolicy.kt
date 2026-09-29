package me.fleey.ppocrv5.call

enum class LocalCallAudioInputMode {
  USER_VOICE_ONLY,
  INCLUDE_SPEAKER_PLAYBACK,
}

data class LocalCallAudioInputConfiguration(
  val preferVoiceCommunicationSource: Boolean,
  val enableEchoCancellation: Boolean,
  val enableNoiseSuppression: Boolean,
)

object LocalCallAudioInputPolicy {
  fun resolve(
    mode: LocalCallAudioInputMode,
    echoCancelerAvailable: Boolean,
  ): LocalCallAudioInputConfiguration {
    if (mode == LocalCallAudioInputMode.INCLUDE_SPEAKER_PLAYBACK) {
      return LocalCallAudioInputConfiguration(
        preferVoiceCommunicationSource = false,
        enableEchoCancellation = false,
        enableNoiseSuppression = false,
      )
    }
    return LocalCallAudioInputConfiguration(
      preferVoiceCommunicationSource = echoCancelerAvailable,
      enableEchoCancellation = echoCancelerAvailable,
      enableNoiseSuppression = true,
    )
  }
}
