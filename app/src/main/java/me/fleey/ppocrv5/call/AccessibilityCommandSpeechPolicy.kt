package me.fleey.ppocrv5.call

class AccessibilityCommandSpeechPolicy {
  fun intentFor(command: AccessibilityCommand): IntentMatch = IntentMatch(
    action = LocalCallAction.ACCESSIBILITY_COMMAND,
    acknowledgement = when (command) {
      is AccessibilityCommand.Swipe -> "好的，我来尝试${command.direction.chineseLabel}滑动。"
      is AccessibilityCommand.ClickListItem -> "好的，我来尝试点击第${command.index}个列表项。"
      is AccessibilityCommand.ClickText -> "好的，我来尝试点击“${command.text}”。"
      is AccessibilityCommand.JdCheckIn -> "好的，我现在帮你去京东打卡。"
      is AccessibilityCommand.WeChatProductCapture ->
        "好的，我现在去微信打开商品并截取关键帧。"
    },
  )

  fun resultFor(command: AccessibilityCommand, succeeded: Boolean): String = when (command) {
    is AccessibilityCommand.Swipe -> if (succeeded) {
      "已完成${command.direction.chineseLabel}滑动。"
    } else {
      "${command.direction.chineseLabel}滑动没有执行成功。"
    }
    is AccessibilityCommand.ClickListItem -> if (succeeded) {
      "已点击第${command.index}个列表项。"
    } else {
      "没有找到可点击的第${command.index}个列表项。"
    }
    is AccessibilityCommand.ClickText -> if (succeeded) {
      "已点击“${command.text}”。"
    } else {
      "没有找到可点击的“${command.text}”。"
    }
    is AccessibilityCommand.JdCheckIn -> if (succeeded) {
      JdAutomationResultFormatter.format(JdAutomationResult.COMPLETED)
    } else {
      JdAutomationResultFormatter.format(JdAutomationResult.TIMED_OUT)
    }
    is AccessibilityCommand.WeChatProductCapture -> if (succeeded) {
      WeChatAutomationResultFormatter.format(WeChatAutomationResult.COMPLETED)
    } else {
      WeChatAutomationResultFormatter.format(WeChatAutomationResult.TIMED_OUT)
    }
  }

  private val SwipeDirection.chineseLabel: String
    get() = when (this) {
      SwipeDirection.UP -> "向上"
      SwipeDirection.DOWN -> "向下"
      SwipeDirection.LEFT -> "向左"
      SwipeDirection.RIGHT -> "向右"
    }
}
