package me.fleey.ppocrv5.call

import android.graphics.Bitmap

data class ScreenTextBlock(
  val text: String,
  val confidence: Float,
  val centerX: Float,
  val centerY: Float,
)

data class ScreenSnapshot(
  val lines: List<String>,
  val capturedAtMillis: Long,
  val frame: Bitmap? = null,
  val sourcePackage: String? = null,
) {
  init {
    require(capturedAtMillis >= 0L) { "capturedAtMillis must not be negative" }
  }

  val summary: String
    get() = lines.joinToString("，")
}
