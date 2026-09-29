package me.fleey.ppocrv5.call

import org.junit.Assert.*
import org.junit.Test

class WeChatCaptureBatchTest {
  @Test fun `each saved frame is followed by one swipe including the final round`() {
    val events = mutableListOf<String>()
    val result = WeChatCaptureBatch(3).run(
      isActive = { true },
      save = { events += "save"; true },
      swipe = { events += "swipe"; true },
      settle = { events += "settle"; true },
    )
    assertEquals(listOf("save", "swipe", "settle", "save", "swipe", "settle", "save", "swipe", "settle"), events)
    assertEquals(3, result.saved)
    assertEquals(3, result.swiped)
    assertEquals(WeChatCaptureBatch.Stop.COMPLETED, result.stop)
  }

  @Test fun `failed save never swipes or retries another page`() {
    val result = WeChatCaptureBatch(10).run({ true }, { false }, { error("must not swipe") }, { true })
    assertEquals(0, result.saved)
    assertEquals(WeChatCaptureBatch.Stop.SAVE_FAILED, result.stop)
  }

  @Test fun `failed swipe stops after the saved frame`() {
    val result = WeChatCaptureBatch(10).run({ true }, { true }, { false }, { error("must not settle") })
    assertEquals(1, result.saved)
    assertEquals(0, result.swiped)
    assertEquals(WeChatCaptureBatch.Stop.SWIPE_FAILED, result.stop)
  }

  @Test fun `cancel during save prevents swipe`() {
    var active = true
    val result = WeChatCaptureBatch(10).run({ active }, { active = false; true }, { error("cancelled") }, { true })
    assertEquals(1, result.saved)
    assertEquals(WeChatCaptureBatch.Stop.CANCELLED, result.stop)
  }

  @Test fun `cancel during page settling prevents next capture`() {
    var saves = 0
    val result = WeChatCaptureBatch(10).run({ true }, { saves++; true }, { true }, { false })
    assertEquals(1, saves)
    assertEquals(WeChatCaptureBatch.Stop.CANCELLED, result.stop)
  }

  @Test fun `preferences default to ten and persist validated numeric input`() {
    var stored: Int? = null
    fun preference() = WeChatCapturePreference({ stored }, { stored = it })
    assertEquals(10, preference().count())
    assertTrue(preference().save(" 2 "))
    assertEquals(2, preference().count())
    listOf("", "0", "-1", "1.5", "51", "abc", "999999999999999").forEach {
      assertFalse(preference().save(it))
      assertEquals(2, preference().count())
    }
    stored = -1
    assertEquals(10, preference().count())
  }

  @Test fun `silent turns finish without speech callbacks and reject stale completion`() {
    val coordinator = LocalCallTurnCoordinator()
    coordinator.beginSilent(1L, 2L, "微信商品关键帧")
    assertEquals("", coordinator.currentSpokenText())
    assertTrue(coordinator.isActive(1L, 2L))
    assertFalse(coordinator.completeSilently(1L, 3L))
    assertTrue(coordinator.completeSpeech(SpeechUtterance(2L, SpeechStage.ACKNOWLEDGEMENT)).ignored)
    assertTrue(coordinator.completeSilently(1L, 2L))
    assertFalse(coordinator.isActive(1L, 2L))
    assertFalse(coordinator.completeSilently(1L, 2L))
  }
}
