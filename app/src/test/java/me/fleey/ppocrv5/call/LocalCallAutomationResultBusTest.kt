package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallAutomationResultBusTest {
  private val bus = TokenizedAccessibilityResultBus()

  @Test
  fun `result completes only its matching token once`() {
    val received = mutableListOf<AccessibilityCommandResult>()
    assertTrue(bus.subscribe(11L, received::add))

    assertFalse(bus.publish(AccessibilityCommandResult(10L, succeeded = true, message = "wrong")))
    assertTrue(bus.publish(AccessibilityCommandResult(11L, succeeded = true, message = "done")))
    assertFalse(bus.publish(AccessibilityCommandResult(11L, succeeded = false, message = "replayed")))

    assertEquals(listOf(AccessibilityCommandResult(11L, true, "done")), received)
  }

  @Test
  fun `duplicate subscriber and invalid tokens are rejected`() {
    assertFalse(bus.subscribe(0L) { })
    assertTrue(bus.subscribe(11L) { })
    assertFalse(bus.subscribe(11L) { })
  }

  @Test
  fun `unsubscribe makes a late callback stale`() {
    assertTrue(bus.subscribe(11L) { error("must not run") })
    assertTrue(bus.isActive(11L))

    assertTrue(bus.unsubscribe(11L))
    assertFalse(bus.isActive(11L))
    assertFalse(bus.publish(AccessibilityCommandResult(11L, true, "late")))
    assertFalse(bus.subscribe(11L) { error("retired token must not be reused") })
  }
}
