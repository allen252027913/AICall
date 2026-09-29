package me.fleey.ppocrv5.call

interface SpeechEngine : AutoCloseable {
  fun start(
    inputMode: LocalCallAudioInputMode = LocalCallAudioInputMode.USER_VOICE_ONLY,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onError: (Throwable) -> Unit,
  )

  fun stop()
}
