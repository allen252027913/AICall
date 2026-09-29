package me.fleey.ppocrv5.call

/** Exactly-once terminal gate for asynchronous accessibility gesture callbacks and watchdogs. */
class AccessibilityGestureCompletion(
  private val onCompleted: () -> Unit,
  private val onFailed: () -> Unit,
  onTimedOut: () -> Unit = onFailed,
) {
  private var terminal = false
  private val onTimedOutEffect = onTimedOut

  fun dispatch(operation: () -> Boolean): Boolean =
    onDispatchResult(runCatching(operation).getOrDefault(false))

  fun onDispatchResult(accepted: Boolean): Boolean {
    if (!accepted) finishIfCurrent(onFailed)
    return accepted
  }

  fun onCompleted() = finishIfCurrent(onCompleted)

  fun onCancelled() = finishIfCurrent(onFailed)

  fun onTimedOut() = finishIfCurrent(onTimedOutEffect)

  private fun finishIfCurrent(effect: () -> Unit) {
    val elected = synchronized(this) {
      if (terminal) false else {
        terminal = true
        true
      }
    }
    if (elected) effect()
  }
}
