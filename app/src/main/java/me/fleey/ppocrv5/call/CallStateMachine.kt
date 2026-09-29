package me.fleey.ppocrv5.call

enum class CallPhase { IDLE, LISTENING, PROCESSING, SPEAKING, ERROR }

data class CallSessionState(
  val callActive: Boolean = false,
  val callEpoch: Long = 0L,
  val screenSharing: Boolean = false,
  val phase: CallPhase = CallPhase.IDLE,
  val transcript: String = "",
  val reply: String = "",
  val screenSummary: String = "",
  val currentCaptureId: String? = null,
  val errorMessage: String? = null,
  val capabilityIssues: List<LocalCallCapabilityIssue> = emptyList(),
)

sealed interface CallEvent {
  data object StartCall : CallEvent
  data object EndCall : CallEvent
  data object ScreenShareGranted : CallEvent
  data object ScreenShareStopped : CallEvent
  data class TranscriptChanged(val text: String) : CallEvent
  data class Processing(val transcript: String) : CallEvent
  data class ScreenUnderstood(val summary: String, val captureId: String?) : CallEvent
  data class ReplyStarted(val text: String) : CallEvent
  data class SilentReply(val text: String) : CallEvent
  data class ReplyInterrupted(val transcript: String) : CallEvent
  data object ReplyFinished : CallEvent
  data class Failed(val message: String) : CallEvent
  data class CapabilityIssueRaised(val issue: LocalCallCapabilityIssue) : CallEvent
  data class CapabilityRestored(val capability: LocalCallCapability) : CallEvent
}

class CallStateMachine(initialState: CallSessionState = CallSessionState()) {
  private var nextCallEpoch = initialState.callEpoch.coerceAtLeast(0L)

  var state: CallSessionState = initialState
    private set

  fun reduce(event: CallEvent): CallSessionState {
    state = when (event) {
      CallEvent.StartCall -> {
        nextCallEpoch = if (nextCallEpoch == Long.MAX_VALUE) 1L else nextCallEpoch + 1L
        CallSessionState(
          callActive = true,
          callEpoch = nextCallEpoch,
          phase = CallPhase.LISTENING,
          capabilityIssues = state.capabilityIssues,
        )
      }
      CallEvent.EndCall -> CallSessionState(
        capabilityIssues = state.capabilityIssues.without(LocalCallCapability.SCREEN_CAPTURE),
      )
      CallEvent.ScreenShareGranted -> state.copy(
        screenSharing = true,
        errorMessage = null,
        capabilityIssues = state.capabilityIssues.without(LocalCallCapability.SCREEN_CAPTURE),
      )
      CallEvent.ScreenShareStopped -> state.copy(
        screenSharing = false,
        screenSummary = "",
        currentCaptureId = null,
      )
      is CallEvent.TranscriptChanged -> if (state.phase == CallPhase.SPEAKING) state else state.copy(transcript = event.text)
      is CallEvent.Processing -> state.copy(phase = CallPhase.PROCESSING, transcript = event.transcript)
      is CallEvent.ScreenUnderstood -> state.copy(
        screenSummary = event.summary,
        currentCaptureId = event.captureId,
      )
      is CallEvent.ReplyStarted -> state.copy(phase = CallPhase.SPEAKING, reply = event.text)
      is CallEvent.SilentReply -> state.copy(phase = CallPhase.LISTENING, reply = event.text, errorMessage = null)
      is CallEvent.ReplyInterrupted -> state.copy(phase = CallPhase.LISTENING, transcript = event.transcript)
      CallEvent.ReplyFinished -> state.copy(phase = CallPhase.LISTENING)
      is CallEvent.Failed -> state.copy(phase = CallPhase.ERROR, errorMessage = event.message)
      is CallEvent.CapabilityIssueRaised -> state.copy(
        capabilityIssues = state.capabilityIssues
          .without(event.issue.capability)
          .plus(event.issue),
      )
      is CallEvent.CapabilityRestored -> state.copy(
        capabilityIssues = state.capabilityIssues.without(event.capability),
      )
    }
    return state
  }

  private fun List<LocalCallCapabilityIssue>.without(
    capability: LocalCallCapability,
  ): List<LocalCallCapabilityIssue> = filterNot { it.capability == capability }
}
