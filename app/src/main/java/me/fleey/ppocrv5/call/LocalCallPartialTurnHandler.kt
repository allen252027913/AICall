package me.fleey.ppocrv5.call

sealed class PartialTurnDecision {
  data class Interrupted(
    val queryToken: Long,
    val stopSpeech: Boolean,
  ) : PartialTurnDecision()

  data object TranscriptOnly : PartialTurnDecision()
  data object Ignored : PartialTurnDecision()
}

class LocalCallPartialTurnHandler(
  private val turnPolicy: LocalCallTurnPolicy,
  private val turnCoordinator: LocalCallTurnCoordinator,
) {
  fun evaluate(partial: String): PartialTurnDecision {
    val spokenText = turnCoordinator.currentSpokenText()
    if (turnPolicy.shouldInterrupt(partial, spokenText)) {
      val interruptedToken = turnCoordinator.interrupt()
      if (interruptedToken != null) {
        return PartialTurnDecision.Interrupted(
          queryToken = interruptedToken,
          stopSpeech = spokenText.isNotBlank(),
        )
      }
    }
    return if (spokenText.isBlank()) PartialTurnDecision.TranscriptOnly else PartialTurnDecision.Ignored
  }
}
