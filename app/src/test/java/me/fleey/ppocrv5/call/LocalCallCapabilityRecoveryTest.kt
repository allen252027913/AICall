package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallCapabilityRecoveryTest {
  @Test
  fun `microphone denial routes retry or application settings`() {
    assertEquals(
      CapabilityRecoveryAction.RETRY_MICROPHONE,
      LocalCallCapabilityRecoveryPolicy.actionFor(
        LocalCallCapabilityIssue.MicrophoneDenied(permanentlyDenied = false),
      ),
    )
    assertEquals(
      CapabilityRecoveryAction.OPEN_APPLICATION_SETTINGS,
      LocalCallCapabilityRecoveryPolicy.actionFor(
        LocalCallCapabilityIssue.MicrophoneDenied(permanentlyDenied = true),
      ),
    )
  }

  @Test
  fun `projection cancellation and revocation expose screen share retry`() {
    assertEquals(
      CapabilityRecoveryAction.RETRY_SCREEN_SHARE,
      LocalCallCapabilityRecoveryPolicy.actionFor(LocalCallCapabilityIssue.ProjectionCancelled),
    )
    assertEquals(
      CapabilityRecoveryAction.RETRY_SCREEN_SHARE,
      LocalCallCapabilityRecoveryPolicy.actionFor(LocalCallCapabilityIssue.ProjectionRevoked),
    )
  }

  @Test
  fun `notification denial offers retry then settings when permanently denied`() {
    assertEquals(
      CapabilityRecoveryAction.RETRY_NOTIFICATIONS,
      LocalCallCapabilityRecoveryPolicy.actionFor(
        LocalCallCapabilityIssue.NotificationDenied(permanentlyDenied = false),
      ),
    )
    assertEquals(
      CapabilityRecoveryAction.OPEN_NOTIFICATION_SETTINGS,
      LocalCallCapabilityRecoveryPolicy.actionFor(
        LocalCallCapabilityIssue.NotificationDenied(permanentlyDenied = true),
      ),
    )
  }

  @Test
  fun `capability recovery removes only its own issue`() {
    val machine = CallStateMachine()
    machine.reduce(
      CallEvent.CapabilityIssueRaised(
        LocalCallCapabilityIssue.MicrophoneDenied(permanentlyDenied = true),
      ),
    )
    machine.reduce(
      CallEvent.CapabilityIssueRaised(
        LocalCallCapabilityIssue.NotificationDenied(permanentlyDenied = false),
      ),
    )

    machine.reduce(CallEvent.CapabilityRestored(LocalCallCapability.MICROPHONE))

    assertFalse(machine.state.capabilityIssues.any { it.capability == LocalCallCapability.MICROPHONE })
    assertTrue(machine.state.capabilityIssues.any { it.capability == LocalCallCapability.NOTIFICATIONS })
  }

  @Test
  fun `projection stop clears stale capture while revocation issue stays recoverable`() {
    val machine = CallStateMachine()
    machine.reduce(CallEvent.StartCall)
    machine.reduce(CallEvent.ScreenShareGranted)
    machine.reduce(CallEvent.ScreenUnderstood("sensitive screen", "capture-1"))

    machine.reduce(CallEvent.ScreenShareStopped)
    machine.reduce(CallEvent.CapabilityIssueRaised(LocalCallCapabilityIssue.ProjectionRevoked))

    assertEquals("", machine.state.screenSummary)
    assertEquals(null, machine.state.currentCaptureId)
    assertEquals(
      CapabilityRecoveryAction.RETRY_SCREEN_SHARE,
      LocalCallCapabilityRecoveryPolicy.actionFor(machine.state.capabilityIssues.single()),
    )

    machine.reduce(CallEvent.ScreenShareGranted)
    assertTrue(machine.state.capabilityIssues.none { it.capability == LocalCallCapability.SCREEN_CAPTURE })
  }

  @Test
  fun `ending a call clears projection recovery without hiding notification issue`() {
    val machine = CallStateMachine()
    machine.reduce(CallEvent.StartCall)
    machine.reduce(CallEvent.CapabilityIssueRaised(LocalCallCapabilityIssue.ProjectionCancelled))
    machine.reduce(
      CallEvent.CapabilityIssueRaised(
        LocalCallCapabilityIssue.NotificationDenied(permanentlyDenied = false),
      ),
    )

    machine.reduce(CallEvent.EndCall)

    assertTrue(machine.state.capabilityIssues.none { it.capability == LocalCallCapability.SCREEN_CAPTURE })
    assertTrue(machine.state.capabilityIssues.any { it.capability == LocalCallCapability.NOTIFICATIONS })
  }
}
