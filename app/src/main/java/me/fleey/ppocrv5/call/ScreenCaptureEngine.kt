package me.fleey.ppocrv5.call

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager

class ScreenCaptureEngine(
  private val context: Context,
  private val onProjectionStopped: (callEpoch: Long) -> Unit,
) : AutoCloseable {
  private val imageThread = HandlerThread("screen-capture").apply { start() }
  private val imageHandler = Handler(imageThread.looper)
  private val projectionLock = Any()
  private var projection: MediaProjection? = null
  private var projectionCallback: MediaProjection.Callback? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var imageReader: ImageReader? = null
  private var width = 0
  private var height = 0

  fun start(resultCode: Int, resultData: Intent, callEpoch: Long) {
    require(callEpoch > 0L) { "A projection must belong to an active call" }
    closeProjection()
    val size = screenSize()
    width = size.first
    height = size.second
    val density = context.resources.displayMetrics.densityDpi
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    val newProjection = requireNotNull(manager.getMediaProjection(resultCode, resultData))
    val callback = object : MediaProjection.Callback() {
      override fun onStop() {
        val shouldNotify = synchronized(projectionLock) {
          if (projection !== newProjection) {
            false
          } else {
            projection = null
            projectionCallback = null
            releaseCaptureTargetsLocked()
            true
          }
        }
        if (shouldNotify) onProjectionStopped(callEpoch)
      }
    }
    try {
      synchronized(projectionLock) {
        projection = newProjection
        projectionCallback = callback
        newProjection.registerCallback(callback, imageHandler)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = newProjection.createVirtualDisplay(
          "local-ai-call-screen",
          width,
          height,
          density,
          DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
          imageReader?.surface,
          null,
          imageHandler,
        )
      }
    } catch (error: Throwable) {
      closeProjection()
      throw error
    }
  }

  fun capture(): Bitmap? = captureFresh()

  fun captureFresh(timeoutMillis: Long = DEFAULT_CAPTURE_TIMEOUT_MILLIS): Bitmap? {
    discardPendingFrame()
    return ScreenCaptureRetryPolicy().capture { captureNext(timeoutMillis) }
  }

  fun discardPendingFrame() {
    val reader = synchronized(projectionLock) { imageReader } ?: return
    runCatching {
      synchronized(projectionLock) {
        if (imageReader === reader) reader.acquireLatestImage()?.close()
      }
    }
  }

  fun captureNext(timeoutMillis: Long = DEFAULT_CAPTURE_TIMEOUT_MILLIS): Bitmap? {
    val reader = synchronized(projectionLock) { imageReader } ?: return null
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    var image = acquireLatestImage(reader)
    while (image == null && SystemClock.elapsedRealtime() < deadline) {
      Thread.sleep(FRAME_POLL_MILLIS)
      image = acquireLatestImage(reader)
    }
    image ?: return null
    return image.use {
      val plane = it.planes.first()
      val rowPadding = plane.rowStride - plane.pixelStride * width
      val paddedWidth = width + rowPadding / plane.pixelStride
      val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
      padded.copyPixelsFromBuffer(plane.buffer)
      if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also {
        padded.recycle()
      }
    }
  }

  private fun screenSize(): Pair<Int, Int> {
    val windowManager = context.getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= 30) {
      val bounds = windowManager.maximumWindowMetrics.bounds
      bounds.width() to bounds.height()
    } else {
      @Suppress("DEPRECATION")
      val metrics = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
      metrics.widthPixels to metrics.heightPixels
    }
  }

  /** Explicit caller stop; only an external MediaProjection callback is reported as revocation. */
  fun stop() = closeProjection()

  private fun acquireLatestImage(reader: ImageReader) = runCatching {
    synchronized(projectionLock) {
      if (imageReader === reader) reader.acquireLatestImage() else null
    }
  }.getOrNull()

  private fun closeProjection() {
    synchronized(projectionLock) {
      val currentProjection = projection
      val currentCallback = projectionCallback
      projection = null
      projectionCallback = null
      releaseCaptureTargetsLocked()
      if (currentProjection != null && currentCallback != null) {
        currentProjection.unregisterCallback(currentCallback)
      }
      currentProjection?.stop()
    }
  }

  private fun releaseCaptureTargetsLocked() {
    virtualDisplay?.release()
    virtualDisplay = null
    imageReader?.close()
    imageReader = null
  }

  override fun close() {
    closeProjection()
    imageThread.quitSafely()
  }

  private companion object {
    const val DEFAULT_CAPTURE_TIMEOUT_MILLIS = 1_000L
    const val FRAME_POLL_MILLIS = 16L
  }
}
