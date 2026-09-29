package me.fleey.ppocrv5.call

/** Pure lifecycle checks shared by projection authorization and revocation handling. */
object LocalCallProjectionLifecyclePolicy {
  fun acceptAuthorizationResult(
    requestedCallEpoch: Long?,
    currentCallEpoch: Long,
    callActive: Boolean,
  ): Boolean = requestedCallEpoch != null &&
    requestedCallEpoch > 0L &&
    callActive &&
    currentCallEpoch == requestedCallEpoch

  fun shouldSurfaceRevocation(
    projectionCallEpoch: Long,
    currentCallEpoch: Long,
    callActive: Boolean,
  ): Boolean = callActive && projectionCallEpoch > 0L && projectionCallEpoch == currentCallEpoch

  fun shouldStopIdleService(callActive: Boolean): Boolean = !callActive
}
