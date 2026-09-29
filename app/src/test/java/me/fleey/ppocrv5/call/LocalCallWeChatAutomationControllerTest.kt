package me.fleey.ppocrv5.call

import android.content.Intent
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallWeChatAutomationControllerTest {
  @Test
  fun `launch flags return an existing WeChat task to LauncherUI`() {
    val flags = WeChatLaunchIntentPolicy.flags

    assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
  }

  @Test
  fun `missing screen share or accessibility rejects before launch`() {
    var launches = 0
    val noShare = controller(screenSharing = false, launch = { launches += 1; WeChatAppLaunchResult.LAUNCHED })
    val noAccessibility = controller(accessibilityEnabled = false, launch = { launches += 1; WeChatAppLaunchResult.LAUNCHED })

    assertEquals(WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.SCREEN_SHARE_DISABLED), noShare.start(11L))
    assertEquals(WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.ACCESSIBILITY_DISABLED), noAccessibility.start(12L))
    assertEquals(0, launches)
  }

  @Test
  fun `successful launch enqueues a tokenized command ready for top search`() {
    val commands = mutableListOf<AutomationCommandEnvelope>()
    val controller = controller(enqueue = { commands += it; true })

    assertTrue(controller.start(11L) is WeChatAutomationStartOutcome.Started)
    val command = commands.single().command as AccessibilityCommand.WeChatProductCapture
    assertEquals(11L, commands.single().token)
    assertEquals(12, command.machine.targetDayOfMonth)
    assertEquals(WeChatAutomationDecision.SEARCH_TOP_SEARCH, command.machine.next(2_000L))
  }

  @Test
  fun `missing app and busy command slot return concrete failures`() {
    assertEquals(
      WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.WECHAT_NOT_INSTALLED),
      controller(launch = { WeChatAppLaunchResult.NOT_INSTALLED }).start(11L),
    )
    assertEquals(
      WeChatAutomationStartOutcome.Rejected(WeChatAutomationResult.BUSY),
      controller(enqueue = { false }).start(12L),
    )
  }

  private fun controller(
    screenSharing: Boolean = true,
    accessibilityEnabled: Boolean = true,
    launch: () -> WeChatAppLaunchResult = { WeChatAppLaunchResult.LAUNCHED },
    enqueue: (AutomationCommandEnvelope) -> Boolean = { true },
  ) = LocalCallWeChatAutomationController(
    screenSharing = { screenSharing },
    accessibilityEnabled = { accessibilityEnabled },
    launcher = WeChatApplicationLauncher(launch),
    enqueue = enqueue,
    elapsedRealtime = { 2_000L },
    currentTimeMillis = {
      GregorianCalendar(SHANGHAI).apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 11, 12, 0, 0)
      }.timeInMillis
    },
    timeZone = { SHANGHAI },
    executeIfActive = { _, action -> action(); true },
  )

  private companion object {
    val SHANGHAI: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
  }
}
