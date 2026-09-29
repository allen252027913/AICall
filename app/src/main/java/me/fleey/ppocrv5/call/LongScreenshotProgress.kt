package me.fleey.ppocrv5.call

import kotlin.math.sqrt

/** No frame quota. Three consecutive completed swipes without movement confirm the stop. */
class LongScreenshotProgress {
  var frames: Int = 1
    private set
  private var stationarySwipes = 0
  val reachedBottom: Boolean get() = stationarySwipes >= 3
  fun advanced() { frames++; stationarySwipes = 0 }
  fun stationary() { stationarySwipes++ }
}

/** Fits the ENTIRE captured extent into one Bitmap; never truncates at the memory limit. */
fun longScreenshotOutputSize(width: Int, height: Long, maxPixels: Long): Pair<Int, Int> {
  require(width > 0 && height > 0 && maxPixels > 0)
  val scale = minOf(1.0, sqrt(maxPixels.toDouble() / width / height), 30_000.0 / height)
  val outputWidth = maxOf(1, (width * scale).toInt())
  val outputHeight = maxOf(1, minOf((height * scale).toLong(), maxPixels / outputWidth).toInt())
  return outputWidth to outputHeight
}
