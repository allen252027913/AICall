package me.fleey.ppocrv5.call

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.TimeZone

enum class WeChatAppLaunchResult { LAUNCHED, NOT_INSTALLED, LAUNCH_FAILED }

object WeChatLaunchIntentPolicy {
  const val flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK or
    Intent.FLAG_ACTIVITY_CLEAR_TOP or
    Intent.FLAG_ACTIVITY_SINGLE_TOP
}

fun interface WeChatApplicationLauncher {
  fun launch(): WeChatAppLaunchResult
}

sealed interface WeChatAutomationStartOutcome {
  data class Started(
    val machineCreatedAtMillis: Long,
    val outerDeadlineMillis: Long,
  ) : WeChatAutomationStartOutcome

  data class Rejected(val result: WeChatAutomationResult) : WeChatAutomationStartOutcome
}

class LocalCallWeChatAutomationController(
  private val screenSharing: () -> Boolean,
  private val accessibilityEnabled: () -> Boolean,
  private val launcher: WeChatApplicationLauncher,
  private val enqueue: (AutomationCommandEnvelope) -> Boolean,
  private val elapsedRealtime: () -> Long,
  private val currentTimeMillis: () -> Long,
  private val timeZone: () -> TimeZone,
  private val executeIfActive: (Long, () -> Unit) -> Boolean,
  private val timing: WeChatAutomationTimingPolicy = WeChatAutomationTimingPolicy.DEFAULT,
) {
  constructor(context: Context) : this(
    screenSharing = { CallSessionStore.state.value.screenSharing },
    accessibilityEnabled = { LocalCallAccessibilityPermission.isEnabled(context.applicationContext) },
    launcher = AndroidWeChatApplicationLauncher(context.applicationContext),
    enqueue = LocalCallAutomationCommandStore::enqueue,
    elapsedRealtime = SystemClock::elapsedRealtime,
    currentTimeMillis = System::currentTimeMillis,
    timeZone = TimeZone::getDefault,
    executeIfActive = LocalCallAutomationCommandStore::executeIfActive,
  )

  fun start(token: Long): WeChatAutomationStartOutcome {
    if (!screenSharing()) {
      return WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.SCREEN_SHARE_DISABLED)
    }
    if (!accessibilityEnabled()) {
      return WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.ACCESSIBILITY_DISABLED)
    }
    var outcome: WeChatAutomationStartOutcome? = null
    val launched = executeIfActive(token) {
      when (launcher.launch()) {
        WeChatAppLaunchResult.NOT_INSTALLED -> outcome =
          WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.WECHAT_NOT_INSTALLED)
        WeChatAppLaunchResult.LAUNCH_FAILED -> outcome =
          WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.LAUNCH_FAILED)
        WeChatAppLaunchResult.LAUNCHED -> {
          val now = elapsedRealtime()
          val targetDayOfMonth = WeChatDateSelectionPolicy.tomorrowDayOfMonth(
            currentTimeMillis(),
            timeZone(),
          )
          val machine = WeChatAutomationStateMachine(
            token,
            now,
            targetDayOfMonth,
            timing,
          ).apply {
            check(onWeChatLaunched(token, now))
          }
          outcome = if (enqueue(
              AutomationCommandEnvelope(
                token = token,
                issuedAtMillis = now,
                command = AccessibilityCommand.WeChatProductCapture(machine),
              ),
            )
          ) {
            WeChatAutomationStartOutcome.Started(now, timing.outerDeadlineFrom(now))
          } else {
            WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.BUSY)
          }
        }
      }
    }
    return if (launched) {
      outcome ?: WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.INTERRUPTED)
    } else {
      WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.INTERRUPTED)
    }
  }

  companion object {
    const val WECHAT_PACKAGE = "com.tencent.mm"
  }
}

private class AndroidWeChatApplicationLauncher(
  private val applicationContext: Context,
) : WeChatApplicationLauncher {
  override fun launch(): WeChatAppLaunchResult {
    val intent = applicationContext.packageManager
      .getLaunchIntentForPackage(LocalCallWeChatAutomationController.WECHAT_PACKAGE)
      ?: return WeChatAppLaunchResult.NOT_INSTALLED
    return runCatching {
      applicationContext.startActivity(intent.addFlags(WeChatLaunchIntentPolicy.flags))
      WeChatAppLaunchResult.LAUNCHED
    }.getOrElse { WeChatAppLaunchResult.LAUNCH_FAILED }
  }
}
