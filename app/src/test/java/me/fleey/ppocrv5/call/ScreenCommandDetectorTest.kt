package me.fleey.ppocrv5.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCommandDetectorTest {
  private val detector = ScreenCommandDetector()

  @Test
  fun `recognizes common Chinese screen questions`() {
    assertTrue(detector.shouldCapture("帮我看看屏幕上是什么"))
    assertTrue(detector.shouldCapture("这个页面讲了什么"))
    assertTrue(detector.shouldCapture("总结一下当前界面"))
  }

  @Test
  fun `does not capture for unrelated conversation`() {
    assertFalse(detector.shouldCapture("今天天气怎么样"))
    assertFalse(detector.shouldCapture("你好"))
  }
}
