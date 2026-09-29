package me.fleey.ppocrv5.call

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusedScreenContentStoreTest {
  @After
  fun tearDown() = FocusedScreenContentStore.clear()

  @Test
  fun `snapshot exposes the most recent foreground source metadata`() {
    FocusedScreenContentStore.update(
      packageName = "com.example.shop",
      activityName = "com.example.shop.ProductActivity",
      timestamp = 42L,
    )

    assertEquals(
      FocusedScreenContent(
        packageName = "com.example.shop",
        activityName = "com.example.shop.ProductActivity",
        timestampMillis = 42L,
      ),
      FocusedScreenContentStore.snapshot(),
    )
  }

  @Test
  fun `clear removes focused screen metadata`() {
    FocusedScreenContentStore.update("com.example.shop", null, 42L)

    FocusedScreenContentStore.clear()

    assertNull(FocusedScreenContentStore.snapshot())
  }
}
