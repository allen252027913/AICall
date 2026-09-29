package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallRuntimeCapabilityReconcilerTest {
  @Test
  fun `request history persists counts and distinguishes the first request`() {
    val counts = mutableMapOf<LocalCallCapability, Int>()
    val granted = mutableSetOf<LocalCallCapability>()
    val history = LocalCallPermissionRequestHistory(
      readCount = { counts[it] ?: 0 },
      writeCount = { capability, count -> counts[capability] = count },
      readWasGranted = granted::contains,
      writeWasGranted = { capability, value -> if (value) granted += capability },
    )

    assertFalse(history.wasRequested(LocalCallCapability.MICROPHONE))
    history.markRequested(LocalCallCapability.MICROPHONE)
    assertTrue(history.wasRequested(LocalCallCapability.MICROPHONE))
    assertFalse(history.wasRequestedBeforeMostRecentRequest(LocalCallCapability.MICROPHONE))

    history.markRequested(LocalCallCapability.MICROPHONE)
    assertTrue(history.wasRequestedBeforeMostRecentRequest(LocalCallCapability.MICROPHONE))
    assertEquals(2, history.requestCount(LocalCallCapability.MICROPHONE))
    assertFalse(history.wasGranted(LocalCallCapability.MICROPHONE))
    history.markGranted(LocalCallCapability.MICROPHONE)
    assertTrue(history.wasGranted(LocalCallCapability.MICROPHONE))
  }

  @Test
  fun `first microphone denial stays retryable even when rationale is false`() {
    assertEquals(
      CallEvent.CapabilityIssueRaised(
        LocalCallCapabilityIssue.MicrophoneDenied(permanentlyDenied = false),
      ),
      LocalCallRuntimeCapabilityReconciler.microphoneRequestResult(
        granted = false,
        wasRequestedBeforeMostRecentRequest = false,
        showRationale = false,
      ),
    )

    val resumed = LocalCallRuntimeCapabilityReconciler.resumeEvents(
      LocalCallRuntimeCapabilitySnapshot(
        sdkInt = 35,
        microphoneGranted = false,
        microphoneWasRequested = true,
        showMicrophoneRationale = false,
        notificationsEnabled = true,
        notificationsWereRequested = true,
        showNotificationRationale = false,
        microphoneRequestCount = 1,
      ),
    ).first() as CallEvent.CapabilityIssueRaised
    assertEquals(
      LocalCallCapabilityIssue.MicrophoneDenied(permanentlyDenied = false),
      resumed.issue,
    )
  }

  @Test
  fun `settings microphone revocation is detected on resume and routes to settings`() {
    val grantedEvents = LocalCallRuntimeCapabilityReconciler.resumeEvents(
      LocalCallRuntimeCapabilitySnapshot(
        sdkInt = 35,
        microphoneGranted = true,
        microphoneWasRequested = true,
        showMicrophoneRationale = false,
        notificationsEnabled = true,
        notificationsWereRequested = true,
        showNotificationRationale = false,
        microphoneWasGrantedBefore = true,
      ),
    )
    assertTrue(
      grantedEvents.contains(CallEvent.CapabilityRestored(LocalCallCapability.MICROPHONE)),
    )

    val events = LocalCallRuntimeCapabilityReconciler.resumeEvents(
      LocalCallRuntimeCapabilitySnapshot(
        sdkInt = 35,
        microphoneGranted = false,
        microphoneWasRequested = true,
        showMicrophoneRationale = false,
        notificationsEnabled = true,
        notificationsWereRequested = true,
        showNotificationRationale = false,
        microphoneWasGrantedBefore = true,
      ),
    )

    val issue = (events.single { it is CallEvent.CapabilityIssueRaised } as CallEvent.CapabilityIssueRaised).issue
    assertEquals(LocalCallCapabilityIssue.MicrophoneDenied(permanentlyDenied = true), issue)
    assertEquals(
      CapabilityRecoveryAction.OPEN_APPLICATION_SETTINGS,
      LocalCallCapabilityRecoveryPolicy.actionFor(issue),
    )
  }

  @Test
  fun `settings notification revocation is detected on resume and routes to notification settings`() {
    val grantedEvents = LocalCallRuntimeCapabilityReconciler.resumeEvents(
      LocalCallRuntimeCapabilitySnapshot(
        sdkInt = 35,
        microphoneGranted = true,
        microphoneWasRequested = true,
        showMicrophoneRationale = false,
        notificationsEnabled = true,
        notificationsWereRequested = true,
        showNotificationRationale = false,
        notificationsWereGrantedBefore = true,
      ),
    )
    assertTrue(
      grantedEvents.contains(CallEvent.CapabilityRestored(LocalCallCapability.NOTIFICATIONS)),
    )

    val events = LocalCallRuntimeCapabilityReconciler.resumeEvents(
      LocalCallRuntimeCapabilitySnapshot(
        sdkInt = 35,
        microphoneGranted = true,
        microphoneWasRequested = true,
        showMicrophoneRationale = false,
        notificationsEnabled = false,
        notificationsWereRequested = true,
        showNotificationRationale = false,
        notificationsWereGrantedBefore = true,
      ),
    )

    val issue = (events.single { it is CallEvent.CapabilityIssueRaised } as CallEvent.CapabilityIssueRaised).issue
    assertEquals(LocalCallCapabilityIssue.NotificationDenied(permanentlyDenied = true), issue)
    assertEquals(
      CapabilityRecoveryAction.OPEN_NOTIFICATION_SETTINGS,
      LocalCallCapabilityRecoveryPolicy.actionFor(issue),
    )
  }

  @Test
  fun `resume reconciles granted and denied capabilities independently`() {
    val events = LocalCallRuntimeCapabilityReconciler.resumeEvents(
      LocalCallRuntimeCapabilitySnapshot(
        sdkInt = 35,
        microphoneGranted = true,
        microphoneWasRequested = true,
        showMicrophoneRationale = false,
        notificationsEnabled = false,
        notificationsWereRequested = false,
        showNotificationRationale = false,
      ),
    )

    assertEquals(
      listOf(
        CallEvent.CapabilityRestored(LocalCallCapability.MICROPHONE),
        CallEvent.CapabilityIssueRaised(
          LocalCallCapabilityIssue.NotificationDenied(permanentlyDenied = false),
        ),
      ),
      events,
    )
  }

  @Test
  fun `pre runtime notification denial uses notification settings`() {
    val event = LocalCallRuntimeCapabilityReconciler.resumeEvents(
      LocalCallRuntimeCapabilitySnapshot(
        sdkInt = 32,
        microphoneGranted = true,
        microphoneWasRequested = false,
        showMicrophoneRationale = false,
        notificationsEnabled = false,
        notificationsWereRequested = false,
        showNotificationRationale = false,
      ),
    ).last() as CallEvent.CapabilityIssueRaised

    assertEquals(
      LocalCallCapabilityIssue.NotificationDenied(permanentlyDenied = true),
      event.issue,
    )
  }

  @Test
  fun `active recorder permission failure becomes a microphone recovery issue`() {
    assertEquals(
      CallEvent.CapabilityIssueRaised(
        LocalCallCapabilityIssue.MicrophoneDenied(permanentlyDenied = true),
      ),
      LocalCallRuntimeCapabilityReconciler.microphoneRecorderError(
        microphoneGranted = false,
        microphoneWasRequested = true,
        message = "offline recognizer failed",
      ),
    )
    assertEquals(
      CallEvent.Failed("offline recognizer failed"),
      LocalCallRuntimeCapabilityReconciler.microphoneRecorderError(
        microphoneGranted = true,
        microphoneWasRequested = true,
        message = "offline recognizer failed",
      ),
    )
  }
}
