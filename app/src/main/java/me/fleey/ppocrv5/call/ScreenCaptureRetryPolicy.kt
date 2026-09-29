package me.fleey.ppocrv5.call

sealed interface ScreenCaptureRetryResult<out T> {
  data class Captured<T>(val value: T) : ScreenCaptureRetryResult<T>
  data object Exhausted : ScreenCaptureRetryResult<Nothing>
  data object Cancelled : ScreenCaptureRetryResult<Nothing>
}

/** Retries a transient screen-frame miss without letting capture loop indefinitely. */
class ScreenCaptureRetryPolicy(
  private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
  private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
  private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
  init {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    require(retryDelayMillis >= 0L) { "retryDelayMillis must not be negative" }
  }

  fun <T : Any> capture(block: () -> T?): T? =
    when (val result = captureWhileActive(isActive = { true }, block = block)) {
      is ScreenCaptureRetryResult.Captured -> result.value
      ScreenCaptureRetryResult.Exhausted,
      ScreenCaptureRetryResult.Cancelled,
      -> null
    }

  /** Revalidates immediately before every attempt, including after each retry pause. */
  fun <T : Any> captureWhileActive(
    isActive: () -> Boolean,
    block: () -> T?,
  ): ScreenCaptureRetryResult<T> {
    repeat(maxAttempts) { attempt ->
      if (!isActive()) return ScreenCaptureRetryResult.Cancelled
      val frame = runCatching(block).getOrNull()
      if (frame != null) return ScreenCaptureRetryResult.Captured(frame)
      if (attempt < maxAttempts - 1 && retryDelayMillis > 0L) pause(retryDelayMillis)
    }
    return ScreenCaptureRetryResult.Exhausted
  }

  private companion object {
    const val DEFAULT_MAX_ATTEMPTS = 3
    const val DEFAULT_RETRY_DELAY_MILLIS = 120L
  }
}
