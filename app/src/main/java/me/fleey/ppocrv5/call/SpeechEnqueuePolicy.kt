package me.fleey.ppocrv5.call

/** Converts an immediate TTS enqueue failure into the same terminal callbacks as playback failure. */
class SpeechEnqueuePolicy(
  private val successCode: Int = SUCCESS,
) {
  fun handle(
    resultCode: Int,
    utterance: SpeechUtterance,
    onError: (SpeechUtterance) -> Unit,
    onFinished: (SpeechUtterance) -> Unit,
  ): Boolean {
    if (resultCode == successCode) return false
    onError(utterance)
    onFinished(utterance)
    return true
  }

  private companion object {
    const val SUCCESS = 0
  }
}
