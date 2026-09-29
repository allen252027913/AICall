package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LocalCallCaptureVisibilityCoordinatorTest {
  @Test
  fun `pip entry delays capture by the longer compositor settle period`() {
    val coordinator = LocalCallCaptureVisibilityCoordinator(
      pause = {},
      clock = { 100L },
    )

    coordinator.onPictureInPictureEntered()

    assertEquals(600L, coordinator.earliestSafeCaptureAtMillis())
  }

  @Test
  fun `ratio updates do not shorten an existing safe capture deadline`() {
    var now = 100L
    val coordinator = LocalCallCaptureVisibilityCoordinator(pause = {}, clock = { now })
    coordinator.onPictureInPictureEntered()
    now = 200L

    coordinator.onPictureInPictureAspectRatioUpdated()

    assertEquals(700L, coordinator.earliestSafeCaptureAtMillis())
  }

  @Test
  fun `pip update during hide is honored before the capture callback`() {
    val delays = mutableListOf<Long>()
    var now = 100L
    val coordinator = LocalCallCaptureVisibilityCoordinator(
      pause = { delay -> delays += delay; now += delay },
      clock = { now },
    )
    coordinator.register(object : LocalCallCaptureVisibilityCoordinator.PipHost {
      override fun hidePictureInPictureForCapture(onHidden: () -> Unit) {
        coordinator.onPictureInPictureAspectRatioUpdated()
        onHidden()
      }

      override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) = onRestored()
    })

    val result = coordinator.captureWithoutPictureInPicture(settleMillis = 0L) { "captured" }

    assertEquals("captured", result)
    assertEquals(listOf(500L), delays)
  }

  @Test
  fun `late hide callback from a destroyed host cannot capture over a replacement host`() {
    val coordinator = LocalCallCaptureVisibilityCoordinator(pause = {}, clock = { 0L })
    val hideRequested = CountDownLatch(1)
    var lateHidden: (() -> Unit)? = null
    coordinator.register(object : LocalCallCaptureVisibilityCoordinator.PipHost {
      override fun hidePictureInPictureForCapture(onHidden: () -> Unit) {
        lateHidden = onHidden
        hideRequested.countDown()
      }

      override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) = onRestored()
    })
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    val result = executor.submit<Boolean> {
      runCatching {
        coordinator.captureWithoutPictureInPicture(timeoutMillis = 500L, settleMillis = 0L) { "captured" }
      }.isSuccess
    }
    try {
      assertTrue(hideRequested.await(1, TimeUnit.SECONDS))
      coordinator.register(object : LocalCallCaptureVisibilityCoordinator.PipHost {
        override fun hidePictureInPictureForCapture(onHidden: () -> Unit) = onHidden()
        override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) = onRestored()
      })
      lateHidden?.invoke()

      assertFalse(result.get(1, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `register and unregister return immediately while capture is held`() {
    val coordinator = LocalCallCaptureVisibilityCoordinator(pause = {}, clock = { 0L })
    coordinator.register(immediatelyHiddenHost())
    val captureStarted = CountDownLatch(1)
    val releaseCapture = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val capture = executor.submit<Boolean> {
        runCatching {
          coordinator.captureWithoutPictureInPicture(settleMillis = 0L) {
            captureStarted.countDown()
            releaseCapture.await(1, TimeUnit.SECONDS)
            "captured"
          }
        }.isSuccess
      }
      assertTrue(captureStarted.await(1, TimeUnit.SECONDS))
      val replacement = immediatelyHiddenHost()
      assertTrue(executor.submit<Boolean> {
        coordinator.register(replacement)
        true
      }.get(100, TimeUnit.MILLISECONDS))
      assertTrue(executor.submit<Boolean> {
        coordinator.unregister(replacement)
        true
      }.get(100, TimeUnit.MILLISECONDS))

      releaseCapture.countDown()
      assertFalse(capture.get(1, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `mid-capture replacement discards the captured result`() {
    val coordinator = LocalCallCaptureVisibilityCoordinator(pause = {}, clock = { 0L })
    coordinator.register(immediatelyHiddenHost())
    val captureStarted = CountDownLatch(1)
    val releaseCapture = CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    try {
      val capture = executor.submit<Boolean> {
        runCatching {
          coordinator.captureWithoutPictureInPicture(settleMillis = 0L) {
            captureStarted.countDown()
            releaseCapture.await(1, TimeUnit.SECONDS)
            "stale frame"
          }
        }.isSuccess
      }
      assertTrue(captureStarted.await(1, TimeUnit.SECONDS))
      coordinator.register(immediatelyHiddenHost())
      releaseCapture.countDown()

      assertFalse(capture.get(1, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `capture lease remains active until asynchronous restore completes`() {
    val coordinator = LocalCallCaptureVisibilityCoordinator(pause = {}, clock = { 0L })
    val restoreRequested = CountDownLatch(1)
    var completeRestore: (() -> Unit)? = null
    var restoreCalls = 0
    coordinator.register(object : LocalCallCaptureVisibilityCoordinator.PipHost {
      override fun hidePictureInPictureForCapture(onHidden: () -> Unit) = onHidden()
      override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) {
        if (restoreCalls++ == 0) {
          completeRestore = onRestored
          restoreRequested.countDown()
        } else {
          onRestored()
        }
      }
    })
    val secondCaptureStarted = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val firstCapture = executor.submit<String> {
        coordinator.captureWithoutPictureInPicture(settleMillis = 0L) { "first" }
      }
      assertTrue(restoreRequested.await(1, TimeUnit.SECONDS))
      val secondCapture = executor.submit<String> {
        coordinator.captureWithoutPictureInPicture(settleMillis = 0L) {
          secondCaptureStarted.countDown()
          "second"
        }
      }

      assertFalse(secondCaptureStarted.await(100, TimeUnit.MILLISECONDS))
      completeRestore?.invoke()
      assertEquals("first", firstCapture.get(1, TimeUnit.SECONDS))
      assertEquals("second", secondCapture.get(1, TimeUnit.SECONDS))
      assertTrue(secondCaptureStarted.await(1, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `missing restore completion times out and permits a future capture`() {
    val coordinator = LocalCallCaptureVisibilityCoordinator(
      pause = {},
      clock = { 0L },
      restoreTimeoutMillis = 50L,
    )
    coordinator.register(object : LocalCallCaptureVisibilityCoordinator.PipHost {
      override fun hidePictureInPictureForCapture(onHidden: () -> Unit) = onHidden()
      override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) = Unit
    })

    assertFalse(runCatching {
      coordinator.captureWithoutPictureInPicture(settleMillis = 0L) { "discarded" }
    }.isSuccess)
    coordinator.register(immediatelyHiddenHost())

    assertEquals("next", coordinator.captureWithoutPictureInPicture(settleMillis = 0L) { "next" })
  }

  @Test
  fun `async UI restore callback completes before capture returns`() {
    val coordinator = LocalCallCaptureVisibilityCoordinator(pause = {}, clock = { 0L })
    val events = mutableListOf<String>()
    val ui = Executors.newSingleThreadExecutor()
    coordinator.register(object : LocalCallCaptureVisibilityCoordinator.PipHost {
      override fun hidePictureInPictureForCapture(onHidden: () -> Unit) = onHidden()
      override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) {
        events += "restore requested"
        ui.submit {
          events += "restored on ui"
          onRestored()
        }
      }
    })
    try {
      assertEquals("captured", coordinator.captureWithoutPictureInPicture(settleMillis = 0L) {
        events += "captured"
        "captured"
      })
      assertEquals(listOf("captured", "restore requested", "restored on ui"), events)
    } finally {
      ui.shutdownNow()
    }
  }

  @Test
  fun `invalidation after restoration but before final lease release discards stale result`() {
    lateinit var coordinator: LocalCallCaptureVisibilityCoordinator
    val invalidationExecutor = Executors.newSingleThreadExecutor()
    coordinator = LocalCallCaptureVisibilityCoordinator(
      pause = {},
      clock = { 0L },
      beforeLeaseFinalization = {
        invalidationExecutor.submit { coordinator.register(immediatelyHiddenHost()) }
          .get(1, TimeUnit.SECONDS)
      },
    )
    coordinator.register(immediatelyHiddenHost())
    try {
      val result = runCatching {
        coordinator.captureWithoutPictureInPicture(settleMillis = 0L) { "stale frame" }
      }

      assertTrue(result.exceptionOrNull() is LocalCallCaptureVisibilityCoordinator.HostChangedException)
    } finally {
      invalidationExecutor.shutdownNow()
    }
  }

  @Test
  fun `interruption while awaiting restoration releases lease and preserves interrupt status`() {
    val restoreRequested = CountDownLatch(1)
    val coordinator = LocalCallCaptureVisibilityCoordinator(
      pause = {},
      clock = { 0L },
      captureLeaseTimeoutMillis = 50L,
    )
    coordinator.register(object : LocalCallCaptureVisibilityCoordinator.PipHost {
      override fun hidePictureInPictureForCapture(onHidden: () -> Unit) = onHidden()
      override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) {
        restoreRequested.countDown()
      }
    })
    val failure = AtomicReference<Throwable>()
    val interrupted = AtomicBoolean()
    val captureThread = Thread {
      try {
        coordinator.captureWithoutPictureInPicture(settleMillis = 0L) { "discarded" }
      } catch (error: Throwable) {
        failure.set(error)
        interrupted.set(Thread.currentThread().isInterrupted)
      }
    }

    captureThread.start()
    assertTrue(restoreRequested.await(1, TimeUnit.SECONDS))
    captureThread.interrupt()
    captureThread.join(1_000L)

    assertFalse(captureThread.isAlive)
    assertTrue(failure.get() is InterruptedException)
    assertTrue(interrupted.get())
    coordinator.register(immediatelyHiddenHost())
    assertEquals(
      "next",
      coordinator.captureWithoutPictureInPicture(settleMillis = 0L) { "next" },
    )
  }

  @Test
  fun `late interrupted restore callback cannot release a newer capture lease`() {
    val firstRestoreRequested = CountDownLatch(1)
    val secondRestoreRequested = CountDownLatch(1)
    val restoreCallbacks = mutableListOf<() -> Unit>()
    val coordinator = LocalCallCaptureVisibilityCoordinator(
      pause = {},
      clock = { 0L },
      captureLeaseTimeoutMillis = 500L,
    )
    coordinator.register(object : LocalCallCaptureVisibilityCoordinator.PipHost {
      override fun hidePictureInPictureForCapture(onHidden: () -> Unit) = onHidden()
      override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) {
        synchronized(restoreCallbacks) {
          if (restoreCallbacks.size < 2) {
            restoreCallbacks += onRestored
            if (restoreCallbacks.size == 1) firstRestoreRequested.countDown()
            if (restoreCallbacks.size == 2) secondRestoreRequested.countDown()
          } else {
            onRestored()
          }
        }
      }
    })
    val firstCapture = Thread {
      runCatching {
        coordinator.captureWithoutPictureInPicture(settleMillis = 0L) { "discarded" }
      }
    }
    val executor = Executors.newFixedThreadPool(2)
    try {
      firstCapture.start()
      assertTrue(firstRestoreRequested.await(1, TimeUnit.SECONDS))
      firstCapture.interrupt()
      firstCapture.join(1_000L)
      assertFalse(firstCapture.isAlive)

      val secondCapture = executor.submit<String> {
        coordinator.captureWithoutPictureInPicture(settleMillis = 0L) { "new frame" }
      }
      assertTrue(secondRestoreRequested.await(1, TimeUnit.SECONDS))
      val callbacks = synchronized(restoreCallbacks) { restoreCallbacks.toList() }

      callbacks[0].invoke()
      val thirdCaptureStarted = CountDownLatch(1)
      val thirdCapture = executor.submit<String> {
        coordinator.captureWithoutPictureInPicture(settleMillis = 0L) {
          thirdCaptureStarted.countDown()
          "newer frame"
        }
      }
      assertFalse(thirdCaptureStarted.await(100, TimeUnit.MILLISECONDS))
      callbacks[1].invoke()
      assertEquals("new frame", secondCapture.get(1, TimeUnit.SECONDS))
      assertEquals("newer frame", thirdCapture.get(1, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `restore timeout discards a successfully captured value exactly once`() {
    val discarded = mutableListOf<String>()
    val coordinator = LocalCallCaptureVisibilityCoordinator(
      pause = {},
      clock = { 0L },
      restoreTimeoutMillis = 20L,
    )
    coordinator.register(object : LocalCallCaptureVisibilityCoordinator.PipHost {
      override fun hidePictureInPictureForCapture(onHidden: () -> Unit) = onHidden()
      override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) = Unit
    })

    val failure = runCatching {
      coordinator.captureWithoutPictureInPicture(
        settleMillis = 0L,
        onDiscardedCapture = { discarded += it },
      ) { "captured frame" }
    }.exceptionOrNull()

    assertTrue(failure is LocalCallCaptureVisibilityCoordinator.RestorationTimeoutException)
    assertEquals(listOf("captured frame"), discarded)
  }

  @Test
  fun `host invalidation discards a successfully captured value exactly once`() {
    lateinit var coordinator: LocalCallCaptureVisibilityCoordinator
    val discarded = mutableListOf<String>()
    coordinator = LocalCallCaptureVisibilityCoordinator(
      pause = {},
      clock = { 0L },
      beforeLeaseFinalization = { coordinator.register(immediatelyHiddenHost()) },
    )
    coordinator.register(immediatelyHiddenHost())

    val failure = runCatching {
      coordinator.captureWithoutPictureInPicture(
        settleMillis = 0L,
        onDiscardedCapture = { discarded += it },
      ) { "stale frame" }
    }.exceptionOrNull()

    assertTrue(failure is LocalCallCaptureVisibilityCoordinator.HostChangedException)
    assertEquals(listOf("stale frame"), discarded)
  }

  @Test
  fun `interrupted restoration discards captured value and preserves interrupt status`() {
    val restoreRequested = CountDownLatch(1)
    val discardCalls = AtomicInteger()
    val failure = AtomicReference<Throwable>()
    val interrupted = AtomicBoolean()
    val coordinator = LocalCallCaptureVisibilityCoordinator(pause = {}, clock = { 0L })
    coordinator.register(object : LocalCallCaptureVisibilityCoordinator.PipHost {
      override fun hidePictureInPictureForCapture(onHidden: () -> Unit) = onHidden()
      override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) {
        restoreRequested.countDown()
      }
    })
    val captureThread = Thread {
      try {
        coordinator.captureWithoutPictureInPicture(
          settleMillis = 0L,
          onDiscardedCapture = { discardCalls.incrementAndGet() },
        ) { "captured frame" }
      } catch (error: Throwable) {
        failure.set(error)
        interrupted.set(Thread.currentThread().isInterrupted)
      }
    }

    captureThread.start()
    assertTrue(restoreRequested.await(1, TimeUnit.SECONDS))
    captureThread.interrupt()
    captureThread.join(1_000L)

    assertFalse(captureThread.isAlive)
    assertTrue(failure.get() is InterruptedException)
    assertTrue(interrupted.get())
    assertEquals(1, discardCalls.get())
  }

  @Test
  fun `finalization failure discards once without allowing cleanup failure to mask it`() {
    val primary = IllegalStateException("finalization failed")
    val cleanup = IllegalArgumentException("cleanup failed")
    var discardCalls = 0
    val coordinator = LocalCallCaptureVisibilityCoordinator(
      pause = {},
      clock = { 0L },
      beforeLeaseFinalization = { throw primary },
    )

    val failure = runCatching {
      coordinator.captureWithoutPictureInPicture(
        onDiscardedCapture = {
          discardCalls += 1
          throw cleanup
        },
      ) { "captured frame" }
    }.exceptionOrNull()

    assertTrue(failure === primary)
    assertEquals(listOf(cleanup), failure?.suppressedExceptions)
    assertEquals(1, discardCalls)
  }

  @Test
  fun `normal return and capture failure do not invoke discard cleanup`() {
    var discardCalls = 0
    val coordinator = LocalCallCaptureVisibilityCoordinator(pause = {}, clock = { 0L })

    assertEquals(
      "captured frame",
      coordinator.captureWithoutPictureInPicture(
        onDiscardedCapture = { discardCalls += 1 },
      ) { "captured frame" },
    )
    val captureFailure = IllegalStateException("capture failed")
    val failure = runCatching {
      coordinator.captureWithoutPictureInPicture<String>(
        onDiscardedCapture = { discardCalls += 1 },
      ) { throw captureFailure }
    }.exceptionOrNull()

    assertTrue(failure === captureFailure)
    assertEquals(0, discardCalls)
  }

  private fun immediatelyHiddenHost() = object : LocalCallCaptureVisibilityCoordinator.PipHost {
    override fun hidePictureInPictureForCapture(onHidden: () -> Unit) = onHidden()
    override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) = onRestored()
  }
}
