package me.fleey.ppocrv5.call

class ScreenCommandDetector {
  private val screenWords = listOf("屏幕", "页面", "界面", "当前", "这个")
  private val intentWords = listOf("看看", "看一下", "是什么", "有什么", "讲了什么", "总结", "读一下")

  fun shouldCapture(transcript: String): Boolean {
    val normalized = transcript.trim().lowercase()
    return screenWords.any(normalized::contains) && intentWords.any(normalized::contains)
  }
}
