package me.fleey.ppocrv5.call

import android.graphics.Bitmap

class ScreenUnderstandingEngine(
  private val minimumConfidence: Float = 0.45f,
) {
  fun understand(
    blocks: List<ScreenTextBlock>,
    capturedAtMillis: Long = System.currentTimeMillis(),
    frame: Bitmap? = null,
    sourcePackage: String? = null,
  ): ScreenSnapshot {
    val lines = blocks
      .asSequence()
      .filter { it.confidence >= minimumConfidence }
      .map { it.copy(text = it.text.trim()) }
      .filter { it.text.isNotBlank() }
      .sortedWith(compareBy<ScreenTextBlock> { it.centerY }.thenBy { it.centerX })
      .map { it.text }
      .distinct()
      .take(MAX_LINES)
      .toList()
    return ScreenSnapshot(lines.toList(), capturedAtMillis, frame, sourcePackage)
  }

  private companion object {
    const val MAX_LINES = 24
  }
}
