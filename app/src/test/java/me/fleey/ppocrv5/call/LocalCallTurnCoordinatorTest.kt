package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallTurnCoordinatorTest {
  private val coordinator = LocalCallTurnCoordinator()
  private val intent = IntentMatch(
    LocalCallAction.READ_SCREEN,
    "好的，我现在替你查看当前屏幕。",
  )

  @Test
  fun `begin returns acknowledgement before operation result`() {
    val request = coordinator.begin(3L, 9L, "看看屏幕", intent)

    assertEquals(intent.acknowledgement, request.text)
    assertEquals(SpeechUtterance(9L, SpeechStage.ACKNOWLEDGEMENT), request.utterance)
    assertEquals(intent.acknowledgement, coordinator.currentSpokenText())
  }

  @Test
  fun `result waits until acknowledgement finishes`() {
    coordinator.begin(3L, 9L, "看看屏幕", intent)

    assertNull(coordinator.completeOperation(3L, 9L, "屏幕上是商品详情页"))
    val completion = coordinator.completeSpeech(
      SpeechUtterance(9L, SpeechStage.ACKNOWLEDGEMENT),
    )

    assertFalse(completion.finished)
    assertEquals("屏幕上是商品详情页", completion.nextSpeech?.text)
    assertEquals(SpeechStage.RESULT, completion.nextSpeech?.utterance?.stage)
  }

  @Test
  fun `finished acknowledgement shows processing until result arrives`() {
    coordinator.begin(3L, 9L, "看看屏幕", intent)

    val completion = coordinator.completeSpeech(
      SpeechUtterance(9L, SpeechStage.ACKNOWLEDGEMENT),
    )
    val result = coordinator.completeOperation(3L, 9L, "屏幕上是商品详情页")

    assertEquals("看看屏幕", completion.processingQuery)
    assertEquals("屏幕上是商品详情页", result?.text)
    assertEquals(SpeechStage.RESULT, result?.utterance?.stage)
  }

  @Test
  fun `interruption invalidates late operation and speech callbacks`() {
    coordinator.begin(3L, 9L, "看看屏幕", intent)

    assertEquals(9L, coordinator.interrupt())
    assertFalse(coordinator.isActive(3L, 9L))
    assertNull(coordinator.completeOperation(3L, 9L, "迟到的结果"))
    assertTrue(
      coordinator.completeSpeech(
        SpeechUtterance(9L, SpeechStage.ACKNOWLEDGEMENT),
      ).ignored,
    )
  }

  @Test
  fun `duplicate acknowledgement callback cannot replay result`() {
    coordinator.begin(3L, 9L, "看看屏幕", intent)
    coordinator.completeOperation(3L, 9L, "屏幕上是商品详情页")
    coordinator.completeSpeech(SpeechUtterance(9L, SpeechStage.ACKNOWLEDGEMENT))

    assertTrue(
      coordinator.completeSpeech(
        SpeechUtterance(9L, SpeechStage.ACKNOWLEDGEMENT),
      ).ignored,
    )
  }

  @Test
  fun `duplicate operation completion cannot replay result`() {
    coordinator.begin(3L, 9L, "看看屏幕", intent)
    coordinator.completeSpeech(SpeechUtterance(9L, SpeechStage.ACKNOWLEDGEMENT))

    assertEquals(
      "屏幕上是商品详情页",
      coordinator.completeOperation(3L, 9L, "屏幕上是商品详情页")?.text,
    )
    assertNull(coordinator.completeOperation(3L, 9L, "重复结果"))
  }
}
