package me.fleey.ppocrv5.call

object WeChatAutomationButtonPolicy {
  fun isEnabled(
    callActive: Boolean,
    screenSharing: Boolean,
    accessibilityEnabled: Boolean,
    phase: CallPhase,
  ): Boolean = callActive &&
    screenSharing &&
    accessibilityEnabled &&
    phase == CallPhase.LISTENING
}
