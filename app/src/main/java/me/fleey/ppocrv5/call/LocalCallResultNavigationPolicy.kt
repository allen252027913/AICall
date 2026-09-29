package me.fleey.ppocrv5.call

/** Compose destinations for the local call experience. */
enum class CallDestination { CALL, RESULTS }

/**
 * Keeps the return-to-app decision independent from Activity/PiP lifecycle details.
 *
 * The caller owns detecting captures made while the app was away. This policy only decides whether
 * those unseen captures warrant switching away from the call surface.
 */
class LocalCallResultNavigationPolicy {
  fun shouldOpenResults(returnedToApp: Boolean, unseenExternalCaptures: Int): Boolean =
    returnedToApp && unseenExternalCaptures > 0
}

/** Invalidates asynchronous resume work when the Activity pauses or the user leaves again. */
class LocalCallResumeEvaluationGate {
  private var nextEpoch = 0L
  private var activeResumeEpoch: Long? = null

  fun beginResume(): Long {
    nextEpoch = nextEpoch.nextEpoch()
    return nextEpoch.also { activeResumeEpoch = it }
  }

  fun invalidateForLeaveOrPause() {
    nextEpoch = nextEpoch.nextEpoch()
    activeResumeEpoch = null
  }

  fun activeEpoch(): Long? = activeResumeEpoch

  fun tryConsume(epoch: Long): Boolean {
    if (activeResumeEpoch != epoch) return false
    activeResumeEpoch = null
    return true
  }

  private fun Long.nextEpoch(): Long = if (this == Long.MAX_VALUE) 1L else this + 1L
}

data class LocalCallHistoryLoadRequest internal constructor(
  internal val generation: Long,
  val resumeEpoch: Long?,
)

data class LocalCallHistoryLoadCompletion internal constructor(
  val evaluateResume: Boolean,
)

/**
 * Makes the newest history snapshot the sole owner of resume navigation. A reload triggered while
 * an Activity is resuming inherits the pending resume epoch, so a superseded loader cannot consume
 * the durable departure marker with an older snapshot.
 */
class LocalCallHistoryLoadCoordinator(
  private val resumeEvaluationGate: LocalCallResumeEvaluationGate,
) {
  private var latestGeneration = 0L

  @Synchronized
  fun beginLoad(resumeEpoch: Long? = null): LocalCallHistoryLoadRequest {
    latestGeneration = latestGeneration.nextGeneration()
    return LocalCallHistoryLoadRequest(
      generation = latestGeneration,
      resumeEpoch = resumeEpoch ?: resumeEvaluationGate.activeEpoch(),
    )
  }

  @Synchronized
  fun claimLatestCompletion(request: LocalCallHistoryLoadRequest): LocalCallHistoryLoadCompletion? {
    if (request.generation != latestGeneration) return null
    return LocalCallHistoryLoadCompletion(
      evaluateResume = request.resumeEpoch?.let(resumeEvaluationGate::tryConsume) ?: false,
    )
  }

  private fun Long.nextGeneration(): Long = if (this == Long.MAX_VALUE) 1L else this + 1L
}

/** Builds an exact departure baseline from persisted frames at or before the leave boundary. */
object LocalCallDepartureBaselinePolicy {
  fun captureIds(
    entries: List<CaptureHistoryEntry>,
    departureCutoffMillis: Long,
    alreadyKnownIds: Set<String>,
  ): Set<String> = buildSet {
    addAll(alreadyKnownIds)
    entries.asSequence()
      .filter { it.capturedAtMillis <= departureCutoffMillis }
      .mapTo(this, CaptureHistoryEntry::id)
  }
}

/**
 * Durable state captured exactly when the user leaves the app.
 *
 * `onAppResumed` is intentionally a no-op without this marker so permission and projection
 * callbacks cannot be mistaken for an external return.
 */
data class LocalCallResultNavigationState(
  val awaitingExternalReturn: Boolean = false,
  val departureCaptureIds: Set<String> = emptySet(),
  val departureCutoffMillis: Long? = null,
) {
  fun markUserLeft(
    captureIds: Set<String>,
    departureCutoffMillis: Long? = this.departureCutoffMillis,
  ): LocalCallResultNavigationState = copy(
    awaitingExternalReturn = true,
    departureCaptureIds = captureIds.toSet(),
    departureCutoffMillis = departureCutoffMillis,
  )

  fun onAppResumed(currentCaptureIds: Set<String>): LocalCallResultNavigationResume {
    if (!awaitingExternalReturn) {
      return LocalCallResultNavigationResume(
        returnedFromUserDeparture = false,
        unseenExternalCaptures = 0,
        nextState = this,
      )
    }
    return LocalCallResultNavigationResume(
      returnedFromUserDeparture = true,
      unseenExternalCaptures = currentCaptureIds.count { it !in departureCaptureIds },
      nextState = LocalCallResultNavigationState(),
    )
  }

  fun toPersistedValue(): LocalCallResultNavigationPersistedValue =
    LocalCallResultNavigationPersistedValue(
      awaitingExternalReturn,
      departureCaptureIds,
      departureCutoffMillis,
    )

  companion object {
    fun fromPersistedValue(value: LocalCallResultNavigationPersistedValue): LocalCallResultNavigationState =
      LocalCallResultNavigationState(
        value.awaitingExternalReturn,
        value.departureCaptureIds,
        value.departureCutoffMillis,
      )
  }
}

data class LocalCallResultNavigationResume(
  val returnedFromUserDeparture: Boolean,
  val unseenExternalCaptures: Int,
  val nextState: LocalCallResultNavigationState,
)

data class LocalCallResultNavigationPersistedValue(
  val awaitingExternalReturn: Boolean,
  val departureCaptureIds: Set<String>,
  val departureCutoffMillis: Long?,
)
