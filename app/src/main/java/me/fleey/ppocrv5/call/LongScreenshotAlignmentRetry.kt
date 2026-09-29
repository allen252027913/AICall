package me.fleey.ppocrv5.call

import java.util.concurrent.CancellationException

/** Re-samples the same scroll position; never dispatches another swipe during recovery. */
class LongScreenshotAlignmentRetry {
  fun <T : Any> run(
    isActive: () -> Boolean,
    captureAndMatch: () -> T?,
    isUncertain: (T) -> Boolean,
    settle: () -> Boolean,
  ): T? {
    fun checkActive() { if (!isActive()) throw CancellationException("长截图已取消") }
    var lastUncertain: T? = null
    repeat(3) { attempt ->
      checkActive()
      val result = captureAndMatch()
      checkActive()
      if (result != null) {
        if (!isUncertain(result)) return result
        lastUncertain = result
      } else if (attempt == 0) {
        return null
      }
      // A missing frame after a mismatch is not evidence of a stationary page.
      if (attempt < 2 && !settle()) throw CancellationException("长截图已取消")
    }
    return lastUncertain
  }
}
