package me.fleey.ppocrv5.call

data class LocalCallTurnIdentity(
  val sessionGeneration: Long,
  val queryToken: Long,
  val action: LocalCallAction,
)

/** Pure lifecycle decisions shared by the foreground service and JVM regression tests. */
class LocalCallServicePolicies {
  fun shouldInvalidateForProjectionStop(turn: LocalCallTurnIdentity?): Boolean =
    turn?.action == LocalCallAction.READ_SCREEN ||
      turn?.action == LocalCallAction.RECOGNIZE_PRODUCT ||
      turn?.action == LocalCallAction.WECHAT_PRODUCT_CAPTURE ||
      turn?.action == LocalCallAction.WECHAT_LONG_SCREENSHOT

  fun canCommitScreen(
    candidate: LocalCallTurnIdentity,
    activeTurn: LocalCallTurnIdentity?,
    callActive: Boolean,
    screenSharing: Boolean,
  ): Boolean = callActive && screenSharing && candidate == activeTurn &&
    (candidate.action == LocalCallAction.READ_SCREEN ||
      candidate.action == LocalCallAction.WECHAT_PRODUCT_CAPTURE)

  fun canContinueProductRecognition(
    candidate: LocalCallTurnIdentity,
    activeTurn: LocalCallTurnIdentity?,
    callActive: Boolean,
    screenSharing: Boolean,
  ): Boolean = callActive && screenSharing && candidate == activeTurn &&
    candidate.action == LocalCallAction.RECOGNIZE_PRODUCT

  fun canPublishTtsError(
    callbackTurn: LocalCallTurnIdentity,
    activeTurn: LocalCallTurnIdentity?,
    callActive: Boolean,
  ): Boolean = callActive && callbackTurn == activeTurn
}

/** Rejects new work before serializing a dependent native-resource close on the same worker. */
class LocalCallWorkerOwner(
  private val submitToWorker: ((() -> Unit) -> Unit),
  private val shutdownWorker: () -> Unit,
) {
  private val lock = Any()
  private var acceptingWork = true

  fun submit(task: () -> Unit): Boolean = synchronized(lock) {
    if (!acceptingWork) return@synchronized false
    submitToWorker(task)
    true
  }

  /** Queues closure after already accepted work and immediately releases the lifecycle caller. */
  fun shutdownWithoutWaiting(closeResource: () -> Unit) = synchronized(lock) {
    if (!acceptingWork) return@synchronized
    acceptingWork = false
    submitToWorker(closeResource)
    shutdownWorker()
  }
}
