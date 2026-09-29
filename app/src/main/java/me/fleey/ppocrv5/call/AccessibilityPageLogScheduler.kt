package me.fleey.ppocrv5.call

class AccessibilityPageLogScheduler(
  private val postDelayed: (Runnable, Long) -> Unit,
  private val removeCallbacks: (Runnable) -> Unit,
  private val logPage: (AccessibilityPageLogRequest) -> Unit,
) {
  private var pending: Runnable? = null

  fun schedule(request: AccessibilityPageLogRequest) {
    cancel()
    lateinit var runnable: Runnable
    runnable = Runnable {
      if (pending !== runnable) return@Runnable
      pending = null
      logPage(request)
    }
    pending = runnable
    postDelayed(runnable, PAGE_SETTLE_MILLIS)
  }

  fun cancel() {
    val runnable = pending ?: return
    pending = null
    removeCallbacks(runnable)
  }

  private companion object {
    const val PAGE_SETTLE_MILLIS = 500L
  }
}
