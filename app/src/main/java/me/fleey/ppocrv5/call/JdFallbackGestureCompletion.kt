package me.fleey.ppocrv5.call

class JdFallbackGestureCompletion(
  private val onCompleted: () -> Unit,
  private val onFailed: () -> Unit,
) {
  private var terminal = false

  fun dispatch(operation: () -> Boolean): Boolean =
    onDispatchResult(runCatching(operation).getOrDefault(false))

  fun onDispatchResult(accepted: Boolean): Boolean {
    if (!accepted) finishIfCurrent(onFailed)
    return accepted
  }

  fun onCompleted() = finishIfCurrent(onCompleted)

  fun onCancelled() = finishIfCurrent(onFailed)

  fun onTimedOut() = finishIfCurrent(onFailed)

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
