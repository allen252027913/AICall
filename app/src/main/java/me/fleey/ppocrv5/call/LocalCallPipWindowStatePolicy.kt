package me.fleey.ppocrv5.call

/** Tracks only the FLAG_SECURE ownership introduced for PiP and capture hiding. */
class LocalCallPipWindowStatePolicy {
  private var secureBeforePip: Boolean? = null
  private var secureBeforeCaptureHide: Boolean? = null

  fun onPipEnter(previouslySecure: Boolean): Boolean {
    if (secureBeforePip == null) secureBeforePip = previouslySecure
    return true
  }

  fun onCaptureHide(currentlySecure: Boolean): Boolean {
    secureBeforeCaptureHide = currentlySecure
    return false
  }

  fun onCaptureRestore(): Boolean? = secureBeforeCaptureHide.also {
    secureBeforeCaptureHide = null
  }

  fun onPipExit(): Boolean? = secureBeforePip.also {
    secureBeforePip = null
  }
}
