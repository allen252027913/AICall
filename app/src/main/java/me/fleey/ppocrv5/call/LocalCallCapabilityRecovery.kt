package me.fleey.ppocrv5.call

enum class LocalCallCapability {
  MICROPHONE,
  NOTIFICATIONS,
  SCREEN_CAPTURE,
}

sealed interface LocalCallCapabilityIssue {
  val capability: LocalCallCapability
  val message: String

  data class MicrophoneDenied(val permanentlyDenied: Boolean) : LocalCallCapabilityIssue {
    override val capability = LocalCallCapability.MICROPHONE
    override val message = if (permanentlyDenied) {
      "麦克风权限已被关闭，请到应用设置中允许后再开始通话。"
    } else {
      "没有麦克风权限，无法开始本地语音通话。"
    }
  }

  data class NotificationDenied(val permanentlyDenied: Boolean) : LocalCallCapabilityIssue {
    override val capability = LocalCallCapability.NOTIFICATIONS
    override val message = "通知权限未开启，通话仍可运行，但常驻通知可能不可见；这不是保活权限。"
  }

  data object ProjectionCancelled : LocalCallCapabilityIssue {
    override val capability = LocalCallCapability.SCREEN_CAPTURE
    override val message = "屏幕共享授权已取消，需要重新授权后才能查看当前页面。"
  }

  data object ProjectionRevoked : LocalCallCapabilityIssue {
    override val capability = LocalCallCapability.SCREEN_CAPTURE
    override val message = "系统已停止屏幕共享，请重新授权后继续查看页面。"
  }
}

enum class CapabilityRecoveryAction {
  RETRY_MICROPHONE,
  RETRY_NOTIFICATIONS,
  RETRY_SCREEN_SHARE,
  OPEN_APPLICATION_SETTINGS,
  OPEN_NOTIFICATION_SETTINGS,
}

object LocalCallCapabilityRecoveryPolicy {
  fun actionFor(issue: LocalCallCapabilityIssue): CapabilityRecoveryAction = when (issue) {
    is LocalCallCapabilityIssue.MicrophoneDenied -> if (issue.permanentlyDenied) {
      CapabilityRecoveryAction.OPEN_APPLICATION_SETTINGS
    } else {
      CapabilityRecoveryAction.RETRY_MICROPHONE
    }
    is LocalCallCapabilityIssue.NotificationDenied -> if (issue.permanentlyDenied) {
      CapabilityRecoveryAction.OPEN_NOTIFICATION_SETTINGS
    } else {
      CapabilityRecoveryAction.RETRY_NOTIFICATIONS
    }
    LocalCallCapabilityIssue.ProjectionCancelled,
    LocalCallCapabilityIssue.ProjectionRevoked,
    -> CapabilityRecoveryAction.RETRY_SCREEN_SHARE
  }

  fun actionLabel(issue: LocalCallCapabilityIssue): String = when (actionFor(issue)) {
    CapabilityRecoveryAction.RETRY_MICROPHONE -> "重新申请麦克风权限"
    CapabilityRecoveryAction.RETRY_NOTIFICATIONS -> "重新申请通知权限"
    CapabilityRecoveryAction.RETRY_SCREEN_SHARE -> "重新授权屏幕共享"
    CapabilityRecoveryAction.OPEN_APPLICATION_SETTINGS -> "打开应用设置"
    CapabilityRecoveryAction.OPEN_NOTIFICATION_SETTINGS -> "打开通知设置"
  }
}
