package me.fleey.ppocrv5.call

enum class WeChatAutomationResult {
  COMPLETED,
  SCREEN_SHARE_DISABLED,
  ACCESSIBILITY_DISABLED,
  WECHAT_NOT_INSTALLED,
  LAUNCH_FAILED,
  BUSY,
  TIMED_OUT,
  WRONG_ROOT,
  INPUT_FAILED,
  INTERRUPTED,
}

object WeChatAutomationResultFormatter {
  fun format(result: WeChatAutomationResult): String = when (result) {
    WeChatAutomationResult.COMPLETED -> "微信商品页面已打开，正在截取关键帧。"
    WeChatAutomationResult.SCREEN_SHARE_DISABLED -> "请先开启屏幕共享，再执行微信商品关键帧。"
    WeChatAutomationResult.ACCESSIBILITY_DISABLED -> "辅助功能尚未开启，请先在通话页开启后再试。"
    WeChatAutomationResult.WECHAT_NOT_INSTALLED -> "没有检测到微信 App，请先安装后再试。"
    WeChatAutomationResult.LAUNCH_FAILED -> "微信 App 启动失败，请稍后再试。"
    WeChatAutomationResult.BUSY -> "当前已有辅助功能操作，请稍后再试。"
    WeChatAutomationResult.TIMED_OUT -> "等待微信目标页面超时，请重新试一次。"
    WeChatAutomationResult.WRONG_ROOT -> "当前活动页面不是微信，已停止自动操作。"
    WeChatAutomationResult.INPUT_FAILED -> "未能在微信搜索框输入“李佳琦”，请重新试一次。"
    WeChatAutomationResult.INTERRUPTED -> "微信自动操作已停止。"
  }
}
