package me.fleey.ppocrv5.call

class LocalCallSpeechInputController(
  private val preference: LocalCallSpeakerInputPreference,
  private val stopListening: () -> Unit,
  private val startListening: (LocalCallAudioInputMode) -> Unit,
) {
  fun currentMode(): LocalCallAudioInputMode = preference.inputMode()

  fun updateSpeakerPlaybackInput(enabled: Boolean, callActive: Boolean) {
    preference.setEnabled(enabled)
    if (!callActive) return
    stopListening()
    startListening(currentMode())
  }
}
