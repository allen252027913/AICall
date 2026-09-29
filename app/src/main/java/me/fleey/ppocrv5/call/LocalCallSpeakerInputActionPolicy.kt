package me.fleey.ppocrv5.call

enum class SpeakerInputAction {
  PERSIST_LOCALLY,
  RESTART_ACTIVE_RECORDER,
}

object LocalCallSpeakerInputActionPolicy {
  fun action(callActive: Boolean): SpeakerInputAction = if (callActive) {
    SpeakerInputAction.RESTART_ACTIVE_RECORDER
  } else {
    SpeakerInputAction.PERSIST_LOCALLY
  }
}
