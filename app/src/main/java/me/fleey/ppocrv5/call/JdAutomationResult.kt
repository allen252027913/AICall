package me.fleey.ppocrv5.call

enum class JdAutomationResult {
  COMPLETED,
  ACCESSIBILITY_DISABLED,
  JD_NOT_INSTALLED,
  LAUNCH_FAILED,
  BUSY,
  TIMED_OUT,
  WRONG_ROOT,
  GESTURE_FAILED,
  INTERRUPTED,
}

object JdAutomationResultFormatter {
  fun format(result: JdAutomationResult): String = when (result) {
    JdAutomationResult.COMPLETED -> "京东打卡已完成，已点击“一键领取”。"
    JdAutomationResult.ACCESSIBILITY_DISABLED ->
      "辅助功能尚未开启，请先在通话页开启后再试。"
    JdAutomationResult.JD_NOT_INSTALLED -> "没有检测到京东 App，请先安装后再试。"
    JdAutomationResult.LAUNCH_FAILED -> "京东 App 启动失败，请稍后再试。"
    JdAutomationResult.BUSY -> "当前已有辅助功能操作，请稍后再试。"
    JdAutomationResult.TIMED_OUT -> "等待京东目标页面超时，请重新试一次。"
    JdAutomationResult.WRONG_ROOT -> "当前活动页面不是京东，已停止自动操作。"
    JdAutomationResult.GESTURE_FAILED -> "京东页面的兜底点击没有执行成功。"
    JdAutomationResult.INTERRUPTED -> "京东自动操作已停止。"
  }
}
