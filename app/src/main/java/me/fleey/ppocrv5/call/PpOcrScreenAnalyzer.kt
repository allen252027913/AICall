package me.fleey.ppocrv5.call

import android.content.Context
import android.graphics.Bitmap
import me.fleey.ppocrv5.ocr.AcceleratorType
import me.fleey.ppocrv5.ocr.OcrEngine

class PpOcrScreenAnalyzer(context: Context) : AutoCloseable {
  private val engine = OcrEngine.create(context, AcceleratorType.CPU).getOrThrow()
  private val understanding = ScreenUnderstandingEngine()

  fun analyze(bitmap: Bitmap): ScreenSnapshot {
    val blocks = detectTextBlocks(bitmap)
    val focusedContent = FocusedScreenContentStore.snapshot()
    return understanding.understand(
      blocks = blocks,
      frame = bitmap,
      sourcePackage = focusedContent?.packageName,
    )
  }

  fun detectTextBlocks(bitmap: Bitmap): List<ScreenTextBlock> = engine.process(bitmap).map {
    ScreenTextBlock(it.text, it.confidence, it.centerX, it.centerY)
  }

  override fun close() = engine.close()
}
