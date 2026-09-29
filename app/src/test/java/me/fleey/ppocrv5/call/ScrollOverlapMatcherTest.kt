package me.fleey.ppocrv5.call

import org.junit.Assert.*
import org.junit.Test

class ScrollOverlapMatcherTest {
  private val matcher = ScrollOverlapMatcher()
  private fun frame(offset: Int, seed: Int = 7): ScrollSample {
    val random = java.util.Random(seed.toLong())
    val page = IntArray(32 * 700) { random.nextInt(256) }
    return ScrollSample(32, 240, IntArray(32 * 240) { i ->
      val y = i / 32
      when {
        y < 20 -> 40
        y >= 220 -> 90
        else -> page[(y - 20 + offset) * 32 + i % 32]
      }
    })
  }

  @Test fun `detects fixed bars and actual scroll distance including small final scroll`() {
    val a = frame(0)
    val b = frame(97)
    val edges = matcher.detectEdges(a, b)
    assertEquals(ScrollEdges(20, 20), edges)
    assertEquals(ScrollMatch.Advanced(97), matcher.match(a, b, edges))
    assertEquals(ScrollMatch.Advanced(7), matcher.match(b, frame(104), edges))
  }

  @Test fun `same frame is stationary rather than appended again`() {
    assertEquals(ScrollMatch.Stationary, matcher.match(frame(12), frame(12), ScrollEdges(20, 20)))
  }

  @Test fun `unrelated pages are rejected rather than joined`() {
    assertEquals(ScrollMatch.Uncertain, matcher.match(frame(0), frame(60, 90), ScrollEdges(20, 20)))
  }

  @Test fun `repetitive content cannot imply an arbitrary scroll distance`() {
    fun stripes(offset: Int) = ScrollSample(32, 240,
      IntArray(32 * 240) { ((it / 32 + offset) % 12) * 20 })
    assertEquals(ScrollMatch.Uncertain, matcher.match(stripes(0), stripes(3), ScrollEdges(0, 0)))
  }

  @Test fun `blank page does not fabricate progress`() {
    val blank = ScrollSample(32, 240, IntArray(32 * 240) { 255 })
    assertEquals(ScrollMatch.Stationary, matcher.match(blank, blank, ScrollEdges(0, 0)))
  }

  @Test fun `rotation is rejected`() {
    assertEquals(ScrollMatch.Uncertain,
      matcher.match(frame(0), ScrollSample(32, 200, IntArray(6400)), ScrollEdges(20, 20)))
  }
}
