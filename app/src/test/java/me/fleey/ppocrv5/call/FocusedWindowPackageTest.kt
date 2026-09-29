package me.fleey.ppocrv5.call

import org.junit.Assert.*
import org.junit.Test

class FocusedWindowPackageTest {
  @Test fun `background pip event must not replace the active window package`() {
    FocusedScreenContentStore.updateFromActiveRoot("com.tencent.mm", "me.fleey.ppocrv5", "PipActivity", 1L)
    assertEquals("com.tencent.mm", FocusedScreenContentStore.snapshot()?.packageName)
    assertNotEquals("PipActivity", FocusedScreenContentStore.snapshot()?.activityName)
    FocusedScreenContentStore.updateFromActiveRoot("other.app", "com.tencent.mm", "WeChatActivity", 2L)
    assertEquals("other.app", FocusedScreenContentStore.snapshot()?.packageName)
    FocusedScreenContentStore.updateFromActiveRoot(null, "com.tencent.mm", null, 3L)
    assertNull(FocusedScreenContentStore.snapshot())
    FocusedScreenContentStore.clear()
  }
}
