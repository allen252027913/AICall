package me.fleey.ppocrv5.call

class LocalReplyEngine {
  fun reply(transcript: String, snapshot: ScreenSnapshot?): String {
    if (ScreenCommandDetector().shouldCapture(transcript)) {
      if (snapshot == null || snapshot.lines.isEmpty()) {
        return "我已在本机查看屏幕，但没有识别到清晰文字。你可以停在文字更清楚的页面再问一次。"
      }
      return "我在当前屏幕识别到：${snapshot.lines.take(MAX_REPLY_LINES).joinToString("，")}。"
    }
    return "这是离线本地 Demo。目前我最擅长回答当前屏幕上的文字内容，你可以说“帮我看看屏幕”。"
  }

  private companion object {
    const val MAX_REPLY_LINES = 8
  }
}
