package me.fleey.ppocrv5.call

enum class JdAutomationClickMethod {
  VIEW_ID,
  ACCESSIBILITY_NODE,
  FALLBACK_COORDINATE,
}

object JdAutomationClickFeedback {
  fun format(target: JdTarget, method: JdAutomationClickMethod): String = when (method) {
    JdAutomationClickMethod.VIEW_ID -> "已通过按钮 ID 点击「${target.label}」"
    JdAutomationClickMethod.ACCESSIBILITY_NODE ->
      "已通过无障碍文字控件或可点击祖先点击「${target.label}」"
    JdAutomationClickMethod.FALLBACK_COORDINATE ->
      "已通过兜底坐标点击「${target.label}」"
  }
}
