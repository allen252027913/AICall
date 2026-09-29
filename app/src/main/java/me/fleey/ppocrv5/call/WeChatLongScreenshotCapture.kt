package me.fleey.ppocrv5.call

import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.CancellationException

data class LongScreenshotResult(
  val bitmap: Bitmap,
  val frames: Int,
  val stop: String,
  val scaled: Boolean,
  val reachedBottom: Boolean,
)

/**
 * Blocking worker API. Capture returns an owned frame, or null if no new frame was rendered.
 * Capture failures must throw; scroll must await gesture completion, settle must await rendering.
 */
class WeChatLongScreenshotCapture(private val cacheDir: File) {
  fun capture(
    isActive: () -> Boolean,
    captureFrame: () -> Bitmap?,
    scroll: () -> Boolean,
    settle: () -> Boolean,
    onProgress: (Int) -> Unit = {},
    onReachedBottom: () -> Unit = {},
    onDiagnostic: (String) -> Unit = {},
  ): LongScreenshotResult {
    fun checkActive() { if (!isActive()) throw CancellationException("长截图已取消，请保持微信页面在前台") }
    LongScreenshotStitcher(cacheDir).use { stitcher ->
      checkActive()
      val first = captureFrame() ?: error("未取得微信屏幕画面")
      if (!isActive()) { first.recycle(); throw CancellationException("长截图已取消") }
      stitcher.add(first)
      val progress = LongScreenshotProgress()
      var stop = "连续三次上滑未发现新内容，已按页面底部结束"
      onProgress(1)
      while (!progress.reachedBottom) {
        checkActive()
        if (!scroll()) { stop = "上滑未成功，仅拼接了部分内容"; break }
        if (!settle()) throw CancellationException("长截图已取消")
        checkActive()
        var attempt = 0
        val addition = LongScreenshotAlignmentRetry().run(
          isActive = isActive,
          captureAndMatch = {
            val frame = captureFrame()
            if (frame != null && !isActive()) { frame.recycle(); throw CancellationException("长截图已取消") }
            val match = frame?.let(stitcher::add)
            attempt++
            onDiagnostic("alignment frames=${stitcher.frameCount} attempt=$attempt result=${match ?: "NO_FRAME"}")
            match
          },
          isUncertain = { it == LongScreenshotStitcher.Addition.UNCERTAIN },
          settle = settle,
        )
        // Static bottom pages may produce no frame. Missing frames AFTER a failed match
        // remain UNCERTAIN in the retry policy and must not count towards page bottom.
        if (addition == null) { progress.stationary(); continue }
        when (addition) {
          LongScreenshotStitcher.Addition.FIRST -> error("重复初始化")
          LongScreenshotStitcher.Addition.APPENDED -> { progress.advanced(); onProgress(progress.frames) }
          LongScreenshotStitcher.Addition.STATIONARY -> progress.stationary()
          LongScreenshotStitcher.Addition.UNCERTAIN -> { stop = "画面对齐重试失败，仅拼接了部分内容"; break }
        }
      }
      checkActive()
      onDiagnostic("stopped frames=${stitcher.frameCount} reachedBottom=${progress.reachedBottom} reason=$stop")
      // All screen reads are finished before notifying: the Toast cannot enter the image.
      if (progress.reachedBottom) onReachedBottom()
      checkActive()
      val bitmap = stitcher.finish()
      return LongScreenshotResult(bitmap, stitcher.frameCount, stop, stitcher.outputWasScaled, progress.reachedBottom)
    }
  }
}
