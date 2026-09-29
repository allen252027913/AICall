package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityPageLogPolicyTest {
  @Test
  fun `window change from any application creates a page log request`() {
    assertEquals(
      AccessibilityPageLogRequest(
        packageName = "com.example.other",
        pageName = "com.example.other.DetailActivity",
      ),
      AccessibilityPageLogPolicy.requestFor(
        eventType = 32,
        windowStateChangedEventType = 32,
        packageName = "com.example.other",
        pageName = "com.example.other.DetailActivity",
      ),
    )
  }

  @Test
  fun `non window change event does not create a page log request`() {
    assertNull(
      AccessibilityPageLogPolicy.requestFor(
        eventType = 8,
        windowStateChangedEventType = 32,
        packageName = "com.example.other",
        pageName = "com.example.other.DetailActivity",
      ),
    )
  }
}
