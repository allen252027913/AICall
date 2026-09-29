package me.fleey.ppocrv5.call

import android.util.Rational

enum class PipContentMode {
  TEXT_ONLY,
  FRAME_WITH_TEXT,
}

data class PipAspectRatio(val width: Int, val height: Int)

object LocalCallPipPolicy {
  fun shouldEnter(callActive: Boolean, screenSharing: Boolean, userEnabled: Boolean): Boolean =
    callActive && screenSharing && userEnabled

  fun contentMode(hasFrame: Boolean): PipContentMode = if (hasFrame) {
    PipContentMode.FRAME_WITH_TEXT
  } else {
    PipContentMode.TEXT_ONLY
  }

  fun previewText(hasFrame: Boolean, ocrText: String): String = when {
    !hasFrame -> "等待你询问当前页面"
    ocrText.isBlank() -> "当前画面未识别到文字"
    else -> ocrText
  }

  /** Android bounds PiP ratios to 100:239 through 239:100. */
  fun aspectRatio(mode: PipContentMode, frameWidth: Int?, frameHeight: Int?): Rational =
    aspectRatioDimensions(mode, frameWidth, frameHeight).let { Rational(it.width, it.height) }

  /** Pure representation used by unit tests; [aspectRatio] is the platform PiP adapter. */
  fun aspectRatioDimensions(
    mode: PipContentMode,
    frameWidth: Int?,
    frameHeight: Int?,
  ): PipAspectRatio {
    if (mode == PipContentMode.TEXT_ONLY || frameWidth == null || frameHeight == null ||
      frameWidth <= 0 || frameHeight <= 0
    ) {
      return PipAspectRatio(COMPACT_WIDTH, COMPACT_HEIGHT)
    }
    val ratio = frameWidth.toDouble() / frameHeight
    return when {
      ratio < MIN_ASPECT_RATIO -> PipAspectRatio(ASPECT_BOUND_SHORT, ASPECT_BOUND_LONG)
      ratio > MAX_ASPECT_RATIO -> PipAspectRatio(ASPECT_BOUND_LONG, ASPECT_BOUND_SHORT)
      else -> PipAspectRatio(frameWidth, frameHeight)
    }
  }

  private const val COMPACT_WIDTH = 1
  private const val COMPACT_HEIGHT = 1
  private const val ASPECT_BOUND_SHORT = 100
  private const val ASPECT_BOUND_LONG = 239
  private const val MIN_ASPECT_RATIO = 100.0 / 239.0
  private const val MAX_ASPECT_RATIO = 239.0 / 100.0
}
