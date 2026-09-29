package me.fleey.ppocrv5.call

import kotlin.math.abs
import kotlin.math.max

/** Sparse horizontal samples, but EVERY vertical row: alignment stays pixel accurate. */
data class ScrollSample(val columns: Int, val height: Int, val luma: IntArray) {
  init { require(columns > 0 && height >= 64 && luma.size == columns * height) }
}

data class ScrollEdges(val top: Int, val bottom: Int)
sealed interface ScrollMatch {
  data class Advanced(val pixels: Int) : ScrollMatch
  data object Stationary : ScrollMatch
  data object Uncertain : ScrollMatch
}

/** Conservative translation matching; animations, repeated patterns and large jumps can fail. */
class ScrollOverlapMatcher {
  fun detectEdges(a: ScrollSample, b: ScrollSample): ScrollEdges {
    require(a.columns == b.columns && a.height == b.height)
    fun fixed(fromBottom: Boolean): Int {
      var changedRows = 0
      for (i in 0 until a.height / 4) {
        val y = if (fromBottom) a.height - 1 - i else i
        changedRows = if (rowError(a, b, y, y) > 5.0) changedRows + 1 else 0
        if (changedRows == 3) return i - 2
      }
      // The boundary is unknown. Keep the whole edge instead of guessing a large crop.
      return 0
    }
    return ScrollEdges(fixed(false), fixed(true))
  }

  fun match(a: ScrollSample, b: ScrollSample, edges: ScrollEdges): ScrollMatch {
    if (a.columns != b.columns || a.height != b.height) return ScrollMatch.Uncertain
    val top = edges.top
    val end = a.height - edges.bottom
    require(top >= 0 && edges.bottom >= 0 && end - top >= 32)
    // A stationary result only means no visible movement, not proof of page bottom.
    if (score(a, b, top, end, 0) <= 1.0) return ScrollMatch.Stationary
    val minOverlap = max(32, (end - top) / 5)
    val candidates = (1..(end - top - minOverlap)).map { shift ->
      shift to score(a, b, top, end, shift)
    }
    val best = candidates.minByOrNull { it.second } ?: return ScrollMatch.Uncertain
    if (best.second > 5.0) return ScrollMatch.Uncertain
    val other = candidates.filter { abs(it.first - best.first) > 3 }.minOfOrNull { it.second }
    if (other != null && other < best.second + 1.0) return ScrollMatch.Uncertain
    return ScrollMatch.Advanced(best.first)
  }

  private fun score(a: ScrollSample, b: ScrollSample, top: Int, end: Int, shift: Int): Double {
    val rows = end - top - shift
    val count = minOf(rows, 96)
    var error = 0.0
    for (i in 0 until count) {
      val y = top + i * (rows - 1) / max(1, count - 1)
      error += rowError(a, b, y + shift, y)
    }
    return error / count
  }

  private fun rowError(a: ScrollSample, b: ScrollSample, ay: Int, by: Int): Double {
    var sum = 0L
    for (x in 0 until a.columns) sum += abs(a.luma[ay * a.columns + x] - b.luma[by * b.columns + x])
    return sum.toDouble() / a.columns
  }
}
