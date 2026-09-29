package me.fleey.ppocrv5

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.fleey.ppocrv5.call.LocalCallOcrProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPreloadTest {
  @Test fun recognitionWorksAfterActivityStartupAndRecreation() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val bitmap = Bitmap.createBitmap(640, 200, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
      drawColor(Color.WHITE)
      drawText("OCR 12345", 40f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 64f
      })
    }
    try {
      ActivityScenario.launch(MainActivity::class.java).use { activity ->
        val first = LocalCallOcrProvider.detectTextBlocks(context, bitmap)
        assertTrue(first.any { it.text.contains("12345") })
        activity.recreate()
        val second = LocalCallOcrProvider.detectTextBlocks(context, bitmap)
        assertTrue(second.any { it.text.contains("12345") })
      }
    } finally { bitmap.recycle() }
  }
}
