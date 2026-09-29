package me.fleey.ppocrv5.call

enum class LocalCallPresentation { PIP, CALL, RESULTS }

object LocalCallPipPresentationPolicy {
  fun route(isInPictureInPicture: Boolean, showingResults: Boolean): LocalCallPresentation = when {
    isInPictureInPicture -> LocalCallPresentation.PIP
    showingResults -> LocalCallPresentation.RESULTS
    else -> LocalCallPresentation.CALL
  }

  fun contentMode(currentCaptureId: String?, hasCurrentCaptureFile: Boolean): PipContentMode =
    LocalCallPipPolicy.contentMode(currentCaptureId != null && hasCurrentCaptureFile)
}

object LocalCallPipParameterUpdatePolicy {
  fun shouldNotifyAspectRatioUpdated(paramsApplied: Boolean): Boolean = paramsApplied
}
