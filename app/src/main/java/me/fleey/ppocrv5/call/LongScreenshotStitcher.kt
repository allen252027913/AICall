package me.fleey.ppocrv5.call

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File
import java.io.IOException
import java.util.UUID

/** Owns submitted frames. Spools strips to disk so only two screen frames remain in memory. */
class LongScreenshotStitcher(
  cacheDir: File,
  private val maxPixels: Long = minOf(12_000_000L, Runtime.getRuntime().maxMemory() / 16),
) : AutoCloseable {
  enum class Addition { FIRST, APPENDED, STATIONARY, UNCERTAIN }
  private val directory = File(cacheDir, "longshot_${UUID.randomUUID()}")
  private val matcher = ScrollOverlapMatcher()
  private var previous: Bitmap? = null
  private var sample: ScrollSample? = null
  private var edges: ScrollEdges? = null
  private val strips = mutableListOf<File>()
  private var bodyHeight = 0L
  private var closed = false
  var frameCount: Int = 0
    private set
  var outputWasScaled: Boolean = false
    private set

  init {
    require(maxPixels > 0)
    check(directory.mkdirs()) { "无法创建长截图临时目录" }
  }

  /** Ownership transfers even if rejected or an exception is thrown. */
  fun add(frame: Bitmap): Addition {
    var retained = false
    try {
      check(!closed)
      val old = previous
      if (old == null) {
        val sampled = sample(frame)
        previous = frame
        sample = sampled
        retained = true
        frameCount = 1
        return Addition.FIRST
      }
      if (frame.width != old.width || frame.height != old.height) return Addition.UNCERTAIN
      val next = sample(frame)
      val region = edges ?: matcher.detectEdges(requireNotNull(sample), next)
      return when (val match = matcher.match(requireNotNull(sample), next, region)) {
        ScrollMatch.Stationary -> Addition.STATIONARY
        ScrollMatch.Uncertain -> Addition.UNCERTAIN
        is ScrollMatch.Advanced -> {
          val end = frame.height - region.bottom
          if (strips.isEmpty()) writeStrip(old, 0, end)
          writeStrip(frame, end - match.pixels, match.pixels)
          edges = region
          previous = frame
          sample = next
          retained = true
          old.recycle()
          frameCount++
          Addition.APPENDED
        }
      }
    } finally {
      if (!retained) frame.recycle()
    }
  }

  /** Returns a new, caller-owned Bitmap. Call recycle() after storing/consuming it. */
  fun finish(): Bitmap {
    check(!closed)
    val last = requireNotNull(previous) { "没有可保存的画面" }
    val bottom = edges?.bottom ?: 0
    val totalHeight = if (strips.isEmpty()) last.height.toLong() else bodyHeight + bottom
    val (outputWidth, outputHeight) = longScreenshotOutputSize(last.width, totalHeight, maxPixels)
    outputWasScaled = outputWidth != last.width || outputHeight.toLong() != totalHeight
    val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(output)
      val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
      if (strips.isEmpty()) {
        canvas.drawBitmap(last, null, android.graphics.Rect(0, 0, outputWidth, outputHeight), paint)
        return output
      }
      fun targetY(sourceY: Long): Int = (sourceY.toDouble() * outputHeight / totalHeight).toInt()
      var y = 0L
      for (file in strips) {
        val strip = BitmapFactory.decodeFile(file.absolutePath) ?: throw IOException("无法读取长图分片")
        try {
          canvas.drawBitmap(strip, null,
            android.graphics.Rect(0, targetY(y), outputWidth, targetY(y + strip.height)), paint)
          y += strip.height
        }
        finally { strip.recycle() }
      }
      if (bottom > 0) {
        val source = android.graphics.Rect(0, last.height - bottom, last.width, last.height)
        canvas.drawBitmap(last, source, android.graphics.Rect(0, targetY(y), outputWidth, outputHeight), paint)
      }
      return output
    } catch (error: Throwable) {
      output.recycle()
      throw error
    }
  }

  private fun writeStrip(bitmap: Bitmap, top: Int, height: Int) {
    val strip = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
    try {
      val file = File(directory, "${strips.size}.png")
      file.outputStream().use { if (!strip.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("长图分片保存失败") }
      strips.add(file)
      bodyHeight += height
    } finally { if (strip !== bitmap) strip.recycle() }
  }

  private fun sample(bitmap: Bitmap): ScrollSample {
    val columns = 48
    val row = IntArray(bitmap.width)
    val pixels = IntArray(columns * bitmap.height)
    for (y in 0 until bitmap.height) {
      bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
      for (x in 0 until columns) {
        // Ignore side scrollbars and rounded display corners.
        val color = row[((.06 + .88 * x / (columns - 1)) * (bitmap.width - 1)).toInt()]
        pixels[y * columns + x] = (77 * ((color ushr 16) and 255) +
          150 * ((color ushr 8) and 255) + 29 * (color and 255)) shr 8
      }
    }
    return ScrollSample(columns, bitmap.height, pixels)
  }

  override fun close() {
    if (closed) return
    closed = true
    previous?.recycle()
    previous = null
    sample = null
    directory.deleteRecursively()
  }
}
