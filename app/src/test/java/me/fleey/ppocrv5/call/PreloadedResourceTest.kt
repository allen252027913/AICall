package me.fleey.ppocrv5.call

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PreloadedResourceTest {
  @Test fun `preload queues initialization without running it on the caller`() {
    val queued = mutableListOf<Runnable>()
    var creations = 0
    val resource = PreloadedResource(Executor { queued.add(it) }) { creations++; Any() }
    resource.preload()
    resource.preload()
    assertEquals(0, creations)
    assertEquals(1, queued.size)
    queued.single().run()
    assertEquals(1, creations)
    val first = resource.withResource { it }
    assertSame(first, resource.withResource { it })
    resource.preload()
    assertEquals(1, creations)
    assertEquals(1, queued.size)
  }

  @Test fun `requests during warmup wait for the same initialization`() {
    val initializer = Executors.newSingleThreadExecutor()
    val callers = Executors.newFixedThreadPool(2)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val creations = AtomicInteger()
    val resource = PreloadedResource(initializer) {
      creations.incrementAndGet()
      started.countDown()
      check(release.await(3, TimeUnit.SECONDS))
      Any()
    }
    try {
      resource.preload()
      assertTrue(started.await(1, TimeUnit.SECONDS))
      val first = callers.submit<Any> { resource.withResource { it } }
      val second = callers.submit<Any> { resource.withResource { it } }
      release.countDown()
      assertSame(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS))
      assertEquals(1, creations.get())
    } finally {
      release.countDown()
      initializer.shutdownNow()
      callers.shutdownNow()
    }
  }

  @Test fun `failed initialization can be retried and successful result is reused`() {
    var attempts = 0
    val resource = PreloadedResource(Executor { it.run() }) {
      if (++attempts == 1) throw IOException("load failed")
      Any()
    }
    assertThrows(IOException::class.java) { resource.withResource { it } }
    resource.preload()
    val loaded = resource.withResource { it }
    assertSame(loaded, resource.withResource { it })
    assertEquals(2, attempts)
  }

  @Test fun `shared native resource is never used concurrently`() {
    val resource = PreloadedResource(Executor { it.run() }) { Any() }
    val callers = Executors.newFixedThreadPool(2)
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val secondEntered = CountDownLatch(1)
    try {
      val first = callers.submit {
        resource.withResource {
          firstEntered.countDown()
          check(releaseFirst.await(3, TimeUnit.SECONDS))
        }
      }
      assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
      val second = callers.submit {
        secondStarted.countDown()
        resource.withResource { secondEntered.countDown() }
      }
      assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
      assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS))
      releaseFirst.countDown()
      first.get(3, TimeUnit.SECONDS)
      second.get(3, TimeUnit.SECONDS)
      assertEquals(0L, secondEntered.count)
    } finally {
      releaseFirst.countDown()
      callers.shutdownNow()
    }
  }
}
