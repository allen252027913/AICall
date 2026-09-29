package me.fleey.ppocrv5.call

import android.content.Context
import android.content.Intent
import android.os.SystemClock

enum class JdAppLaunchResult {
  LAUNCHED,
  NOT_INSTALLED,
  LAUNCH_FAILED,
}

fun interface JdApplicationLauncher {
  fun launch(): JdAppLaunchResult
}

sealed interface JdAutomationStartOutcome {
  data class Started(
    val machineCreatedAtMillis: Long,
    val outerDeadlineMillis: Long,
  ) : JdAutomationStartOutcome
  data class Rejected(val result: JdAutomationResult) : JdAutomationStartOutcome
}

class LocalCallJdAutomationController(
  private val accessibilityEnabled: () -> Boolean,
  private val launcher: JdApplicationLauncher,
  private val enqueue: (AutomationCommandEnvelope) -> Boolean,
  private val elapsedRealtime: () -> Long,
  private val executeIfActive: (Long, () -> Unit) -> Boolean,
  private val timing: JdAutomationTimingPolicy = JdAutomationTimingPolicy.DEFAULT,
) {
  constructor(context: Context) : this(
    accessibilityEnabled = { LocalCallAccessibilityPermission.isEnabled(context.applicationContext) },
    launcher = AndroidJdApplicationLauncher(context.applicationContext),
    enqueue = LocalCallAutomationCommandStore::enqueue,
    elapsedRealtime = SystemClock::elapsedRealtime,
    executeIfActive = LocalCallAutomationCommandStore::executeIfActive,
  )

  fun start(token: Long): JdAutomationStartOutcome {
    if (!accessibilityEnabled()) {
      return JdAutomationStartOutcome.Rejected(JdAutomationResult.ACCESSIBILITY_DISABLED)
    }
    var outcome: JdAutomationStartOutcome? = null
    val launched = executeIfActive(token) {
      when (launcher.launch()) {
        JdAppLaunchResult.NOT_INSTALLED ->
          outcome = JdAutomationStartOutcome.Rejected(JdAutomationResult.JD_NOT_INSTALLED)
        JdAppLaunchResult.LAUNCH_FAILED ->
          outcome = JdAutomationStartOutcome.Rejected(JdAutomationResult.LAUNCH_FAILED)
        JdAppLaunchResult.LAUNCHED -> {
          val now = elapsedRealtime()
          val machine = JdAutomationStateMachine(token = token, createdAtMillis = now).apply {
            check(onJdLaunched(token, now))
          }
          outcome = if (enqueue(
              AutomationCommandEnvelope(
                token = token,
                issuedAtMillis = now,
                command = AccessibilityCommand.JdCheckIn(machine),
              ),
            )
          ) {
            JdAutomationStartOutcome.Started(
              machineCreatedAtMillis = now,
              outerDeadlineMillis = timing.outerDeadlineFrom(now),
            )
          } else {
            JdAutomationStartOutcome.Rejected(JdAutomationResult.BUSY)
          }
        }
      }
    }
    return if (launched) outcome ?: JdAutomationStartOutcome.Rejected(JdAutomationResult.INTERRUPTED)
    else JdAutomationStartOutcome.Rejected(JdAutomationResult.INTERRUPTED)
  }

  companion object {
    const val JD_PACKAGE = "com.jingdong.app.mall"
  }
}

private class AndroidJdApplicationLauncher(
  private val applicationContext: Context,
) : JdApplicationLauncher {
  override fun launch(): JdAppLaunchResult {
    val intent = applicationContext.packageManager
      .getLaunchIntentForPackage(LocalCallJdAutomationController.JD_PACKAGE)
      ?: return JdAppLaunchResult.NOT_INSTALLED
    return runCatching {
      applicationContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      JdAppLaunchResult.LAUNCHED
    }.getOrElse { JdAppLaunchResult.LAUNCH_FAILED }
  }
}
