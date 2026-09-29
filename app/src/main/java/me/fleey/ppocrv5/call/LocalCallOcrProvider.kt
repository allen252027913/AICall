package me.fleey.ppocrv5.call

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/** Owns one CPU OCR instance for the process, not for a particular Activity or call. */
object LocalCallOcrProvider {
  private const val TAG = "LocalCallOcr"
  private val initializer = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "local-call-ocr-init")
  }
  private var resource: PreloadedResource<PpOcrScreenAnalyzer>? = null

  fun preload(context: Context) {
    runCatching { resource(context).preload() }
      .onFailure { Log.w(TAG, "ocr_preload_schedule_failed", it) }
  }

  fun detectTextBlocks(context: Context, bitmap: Bitmap): List<ScreenTextBlock> =
    withAnalyzer(context) { it.detectTextBlocks(bitmap) }

  fun analyze(context: Context, bitmap: Bitmap): ScreenSnapshot =
    withAnalyzer(context) { it.analyze(bitmap) }

  private fun <T> withAnalyzer(context: Context, block: (PpOcrScreenAnalyzer) -> T): T {
    check(Looper.myLooper() != Looper.getMainLooper()) { "OCR must run off the main thread" }
    val started = SystemClock.elapsedRealtime()
    return resource(context).withResource { analyzer ->
      Log.d(TAG, "ocr_acquired instance=${System.identityHashCode(analyzer)} waitMs=${SystemClock.elapsedRealtime() - started}")
      block(analyzer)
    }
  }

  @Synchronized
  private fun resource(context: Context): PreloadedResource<PpOcrScreenAnalyzer> {
    resource?.let { return it }
    // Capture only the application, so reopening the Activity cannot retain its old window.
    val app = context.applicationContext
    return PreloadedResource(initializer) {
      val started = SystemClock.elapsedRealtime()
      Log.d(TAG, "ocr_init_start thread=${Thread.currentThread().name}")
      try {
        PpOcrScreenAnalyzer(app).also {
          Log.d(TAG, "ocr_init_ready instance=${System.identityHashCode(it)} elapsedMs=${SystemClock.elapsedRealtime() - started}")
        }
      } catch (error: Throwable) {
        Log.w(TAG, "ocr_init_failed elapsedMs=${SystemClock.elapsedRealtime() - started}", error)
        throw error
      }
    }.also { resource = it }
  }
}
