package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallPartialTurnHandlerTest {
  private val coordinator = LocalCallTurnCoordinator()
  private val handler = LocalCallPartialTurnHandler(LocalCallTurnPolicy(), coordinator)

  @Test
  fun `non stop partial is ignored while acknowledgement is speaking`() {
    coordinator.begin(
      generation = 1L,
      queryToken = 2L,
      query = "看看屏幕",
      intent = IntentMatch(LocalCallAction.READ_SCREEN, "好的，我现在替你查看当前屏幕。"),
    )

    assertEquals(PartialTurnDecision.Ignored, handler.evaluate("我还有一个问题"))
  }

  @Test
  fun `old query token cannot publish a result after a newer turn begins`() {
    val intent = IntentMatch(LocalCallAction.READ_SCREEN, "好的，我现在替你查看当前屏幕。")
    coordinator.begin(1L, 2L, "看看屏幕", intent)
    coordinator.begin(1L, 3L, "再看看屏幕", intent)

    assertTrue(coordinator.completeOperation(1L, 2L, "旧结果") == null)
  }
}
