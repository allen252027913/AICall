package me.fleey.ppocrv5.call

import android.content.Context

data class LocalCallRuntimeCapabilitySnapshot(
  val sdkInt: Int,
  val microphoneGranted: Boolean,
  val microphoneWasRequested: Boolean,
  val showMicrophoneRationale: Boolean,
  val notificationsEnabled: Boolean,
  val notificationsWereRequested: Boolean,
  val showNotificationRationale: Boolean,
  val microphoneWasGrantedBefore: Boolean = false,
  val microphoneRequestCount: Int = if (microphoneWasRequested) 1 else 0,
  val notificationsWereGrantedBefore: Boolean = false,
  val notificationRequestCount: Int = if (notificationsWereRequested) 1 else 0,
)

/**
 * Converts the current platform permission state into state-machine events.
 *
 * Android reports `shouldShowRequestPermissionRationale == false` both before the first request and
 * after a permanent denial. The persisted request history is therefore part of the classification.
 */
object LocalCallRuntimeCapabilityReconciler {
  fun resumeEvents(snapshot: LocalCallRuntimeCapabilitySnapshot): List<CallEvent> = listOf(
    if (snapshot.microphoneGranted) {
      CallEvent.CapabilityRestored(LocalCallCapability.MICROPHONE)
    } else {
      CallEvent.CapabilityIssueRaised(
        LocalCallCapabilityIssue.MicrophoneDenied(
          permanentlyDenied = !snapshot.showMicrophoneRationale &&
            (snapshot.microphoneWasGrantedBefore || snapshot.microphoneRequestCount > 1),
        ),
      )
    },
    if (snapshot.notificationsEnabled) {
      CallEvent.CapabilityRestored(LocalCallCapability.NOTIFICATIONS)
    } else {
      CallEvent.CapabilityIssueRaised(
        LocalCallCapabilityIssue.NotificationDenied(
          permanentlyDenied = snapshot.sdkInt < 33 ||
            !snapshot.showNotificationRationale &&
            (snapshot.notificationsWereGrantedBefore || snapshot.notificationRequestCount > 1),
        ),
      )
    },
  )

  fun microphoneRequestResult(
    granted: Boolean,
    wasRequestedBeforeMostRecentRequest: Boolean,
    showRationale: Boolean,
  ): CallEvent = if (granted) {
    CallEvent.CapabilityRestored(LocalCallCapability.MICROPHONE)
  } else {
    CallEvent.CapabilityIssueRaised(
      LocalCallCapabilityIssue.MicrophoneDenied(
        permanentlyDenied = wasRequestedBeforeMostRecentRequest && !showRationale,
      ),
    )
  }

  fun notificationRequestResult(
    granted: Boolean,
    wasRequestedBeforeMostRecentRequest: Boolean,
    showRationale: Boolean,
  ): CallEvent = if (granted) {
    CallEvent.CapabilityRestored(LocalCallCapability.NOTIFICATIONS)
  } else {
    CallEvent.CapabilityIssueRaised(
      LocalCallCapabilityIssue.NotificationDenied(
        permanentlyDenied = wasRequestedBeforeMostRecentRequest && !showRationale,
      ),
    )
  }

  fun microphoneRecorderError(
    microphoneGranted: Boolean,
    microphoneWasRequested: Boolean,
    message: String,
    microphoneWasGrantedBefore: Boolean = false,
  ): CallEvent = if (microphoneGranted) {
    CallEvent.Failed(message)
  } else {
    CallEvent.CapabilityIssueRaised(
      LocalCallCapabilityIssue.MicrophoneDenied(
        permanentlyDenied = microphoneWasRequested || microphoneWasGrantedBefore,
      ),
    )
  }
}

/** Durable request counts used to disambiguate first denial from "don't ask again". */
class LocalCallPermissionRequestHistory internal constructor(
  private val readCount: (LocalCallCapability) -> Int,
  private val writeCount: (LocalCallCapability, Int) -> Unit,
  private val readWasGranted: (LocalCallCapability) -> Boolean = { false },
  private val writeWasGranted: (LocalCallCapability, Boolean) -> Unit = { _, _ -> },
) {
  constructor(context: Context) : this(
    readCount = { capability ->
      context.applicationContext
        .getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
        .getInt(capability.preferenceKey(), 0)
    },
    writeCount = { capability, count ->
      context.applicationContext
        .getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
        .edit()
        .putInt(capability.preferenceKey(), count)
        .apply()
    },
    readWasGranted = { capability ->
      context.applicationContext
        .getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
        .getBoolean(capability.grantedPreferenceKey(), false)
    },
    writeWasGranted = { capability, granted ->
      context.applicationContext
        .getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(capability.grantedPreferenceKey(), granted)
        .apply()
    },
  )

  fun requestCount(capability: LocalCallCapability): Int =
    readCount(capability).coerceAtLeast(0)

  fun wasRequested(capability: LocalCallCapability): Boolean = requestCount(capability) > 0

  fun wasGranted(capability: LocalCallCapability): Boolean = readWasGranted(capability)

  fun markGranted(capability: LocalCallCapability) {
    if (!wasGranted(capability)) writeWasGranted(capability, true)
  }

  /** Must be invoked immediately before launching the platform permission request. */
  fun markRequested(capability: LocalCallCapability) {
    val current = requestCount(capability)
    writeCount(capability, if (current == Int.MAX_VALUE) current else current + 1)
  }

  /** Called from the result callback, after [markRequested] for the just-finished request. */
  fun wasRequestedBeforeMostRecentRequest(capability: LocalCallCapability): Boolean =
    requestCount(capability) > 1

  private companion object {
    const val PREFERENCE_FILE = "local_call"
  }
}

private fun LocalCallCapability.preferenceKey(): String = when (this) {
  LocalCallCapability.MICROPHONE -> "microphone_permission_request_count"
  LocalCallCapability.NOTIFICATIONS -> "notification_permission_request_count"
  LocalCallCapability.SCREEN_CAPTURE -> error("Screen capture uses a per-session system consent flow")
}

private fun LocalCallCapability.grantedPreferenceKey(): String = when (this) {
  LocalCallCapability.MICROPHONE -> "microphone_permission_was_granted"
  LocalCallCapability.NOTIFICATIONS -> "notification_permission_was_granted"
  LocalCallCapability.SCREEN_CAPTURE -> error("Screen capture uses a per-session system consent flow")
}
