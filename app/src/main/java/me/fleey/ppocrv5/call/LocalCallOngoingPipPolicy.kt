package me.fleey.ppocrv5.call

enum class OngoingPipAction {
  NONE,
  KEEP_SHOWING,
  FINISH_ACTIVITY,
}

object LocalCallOngoingPipPolicy {
  fun action(
    isInPictureInPicture: Boolean,
    callActive: Boolean,
    screenSharing: Boolean,
  ): OngoingPipAction = when {
    !isInPictureInPicture -> OngoingPipAction.NONE
    !callActive || !screenSharing -> OngoingPipAction.FINISH_ACTIVITY
    else -> OngoingPipAction.KEEP_SHOWING
  }
}
