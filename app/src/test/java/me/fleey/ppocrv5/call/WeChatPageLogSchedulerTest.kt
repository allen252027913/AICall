package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityPageLogSchedulerTest {
  @Test
  fun `page traversal runs 500 milliseconds after a new page event`() {
    val queue = ManualDelayedTaskQueue()
    val loggedPages = mutableListOf<AccessibilityPageLogRequest>()
    val scheduler = AccessibilityPageLogScheduler(queue::postDelayed, queue::remove) { loggedPages += it }
    val page = AccessibilityPageLogRequest("com.example.other", "DetailActivity")

    scheduler.schedule(page)

    assertTrue(loggedPages.isEmpty())
    assertEquals(500L, queue.pending.single().delayMillis)

    queue.pending.single().runnable.run()

    assertEquals(listOf(page), loggedPages)
  }

  @Test
  fun `new page event replaces the pending page traversal`() {
    val queue = ManualDelayedTaskQueue()
    val loggedPages = mutableListOf<AccessibilityPageLogRequest>()
    val scheduler = AccessibilityPageLogScheduler(queue::postDelayed, queue::remove) { loggedPages += it }
    val firstPage = AccessibilityPageLogRequest("com.first", "FirstActivity")
    val secondPage = AccessibilityPageLogRequest("com.second", "SecondActivity")

    scheduler.schedule(firstPage)
    val firstRunnable = queue.pending.single().runnable
    scheduler.schedule(secondPage)

    firstRunnable.run()
    queue.pending.single().runnable.run()

    assertEquals(listOf(secondPage), loggedPages)
  }

  @Test
  fun `cancel prevents the pending page traversal`() {
    val queue = ManualDelayedTaskQueue()
    val loggedPages = mutableListOf<AccessibilityPageLogRequest>()
    val scheduler = AccessibilityPageLogScheduler(queue::postDelayed, queue::remove) { loggedPages += it }

    scheduler.schedule(AccessibilityPageLogRequest("com.example", "PageActivity"))
    val pendingRunnable = queue.pending.single().runnable
    scheduler.cancel()
    pendingRunnable.run()

    assertTrue(queue.pending.isEmpty())
    assertTrue(loggedPages.isEmpty())
  }

  private class ManualDelayedTaskQueue {
    val pending = mutableListOf<PendingTask>()

    fun postDelayed(runnable: Runnable, delayMillis: Long) {
      pending += PendingTask(runnable, delayMillis)
    }

    fun remove(runnable: Runnable) {
      pending.removeAll { it.runnable === runnable }
    }
  }

  private data class PendingTask(val runnable: Runnable, val delayMillis: Long)
}
