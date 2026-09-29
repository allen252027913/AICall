package me.fleey.ppocrv5.call

import android.graphics.Bitmap
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallScreenCommitCoordinatorTest {
  @Test
  fun `stalled persistence does not hold the session validation lock`() {
    val sessionLock = Any()
    val active = AtomicBoolean(true)
    val saveStarted = CountDownLatch(1)
    val allowSave = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val coordinator = LocalCallScreenCommitCoordinator(
      canStart = { synchronized(sessionLock) { active.get() } },
      stage = {
        saveStarted.countDown()
        check(allowSave.await(2, TimeUnit.SECONDS))
        "staged"
      },
      canPrepare = { synchronized(sessionLock) { active.get() } },
      prepareCommit = { "prepared" },
      commitIfValid = { "entry" },
      discardStaged = { },
      rollbackPrepared = { },
      afterCommit = { },
    )
    val writer = Thread {
      coordinator.commit()
      finished.countDown()
    }.apply { start() }

    assertTrue(saveStarted.await(1, TimeUnit.SECONDS))
    val stopCompleted = CountDownLatch(1)
    Thread {
      synchronized(sessionLock) { active.set(false) }
      stopCompleted.countDown()
    }.start()

    assertTrue("stop must not wait for JPEG persistence", stopCompleted.await(250, TimeUnit.MILLISECONDS))
    allowSave.countDown()
    assertTrue(finished.await(1, TimeUnit.SECONDS))
    writer.join(1_000L)
  }

  @Test
  fun `stage that becomes stale is discarded before commit and never published`() {
    val active = AtomicBoolean(true)
    val saveStarted = CountDownLatch(1)
    val allowSave = CountDownLatch(1)
    val committed = mutableListOf<String>()
    val published = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    val coordinator = LocalCallScreenCommitCoordinator(
      canStart = active::get,
      stage = {
        saveStarted.countDown()
        check(allowSave.await(2, TimeUnit.SECONDS))
        "stale-stage"
      },
      canPrepare = active::get,
      prepareCommit = { staged ->
        committed += staged
        "prepared-entry"
      },
      commitIfValid = { prepared -> prepared.takeIf { active.get() } },
      discardStaged = discarded::add,
      rollbackPrepared = { },
      afterCommit = published::add,
    )
    val writer = Thread(coordinator::commit).apply { start() }

    assertTrue(saveStarted.await(1, TimeUnit.SECONDS))
    active.set(false)
    allowSave.countDown()
    writer.join(1_000L)

    assertFalse(writer.isAlive)
    assertTrue(committed.isEmpty())
    assertTrue(published.isEmpty())
    assertEquals(listOf("stale-stage"), discarded)
  }

  @Test
  fun `cancellation after invisible prepare rolls back without observable entry or eviction`() {
    val root = Files.createTempDirectory("local-call-transaction").toFile()
    try {
      val repository = LocalCallCaptureHistoryRepository(
        rootDirectory = root,
        maxEntries = 1,
        jpegWriter = { _, target -> target.writeBytes(byteArrayOf(1, 2, 3)); true },
      )
      val first = repository.save(uninitializedBitmap(), "first", "com.example", 1L)
      val sessionLock = Any()
      var active = true
      val prepared = CountDownLatch(1)
      val allowFinalValidation = CountDownLatch(1)
      val finished = CountDownLatch(1)
      val coordinator = LocalCallScreenCommitCoordinator(
        canStart = { synchronized(sessionLock) { active } },
        stage = { repository.stage(uninitializedBitmap(), "stale", "com.example", 2L) },
        canPrepare = { synchronized(sessionLock) { active } },
        prepareCommit = repository::prepareCommit,
        commitIfValid = { transaction ->
          prepared.countDown()
          check(allowFinalValidation.await(2, TimeUnit.SECONDS))
          synchronized(sessionLock) {
            if (active) repository.publish(transaction) else null
          }
        },
        discardStaged = repository::discard,
        rollbackPrepared = repository::rollback,
        afterCommit = repository::finishPublication,
      )
      val writer = Thread {
        coordinator.commit()
        finished.countDown()
      }.apply { start() }

      assertTrue(prepared.await(1, TimeUnit.SECONDS))
      val stopFinished = CountDownLatch(1)
      Thread {
        synchronized(sessionLock) { active = false }
        stopFinished.countDown()
      }.start()
      assertTrue("stop must not wait for invisible preparation", stopFinished.await(250, TimeUnit.MILLISECONDS))
      assertEquals(listOf(first.id), repository.load().map { it.id })
      allowFinalValidation.countDown()
      assertTrue(finished.await(1, TimeUnit.SECONDS))
      writer.join(1_000L)

      assertFalse(writer.isAlive)
      assertEquals(listOf(first.id), repository.load().map { it.id })
      assertTrue(File(root, first.imageFileName).isFile)
      assertEquals(1, root.listFiles().orEmpty().count { it.extension == "jpg" })
      assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".pending") })
    } finally {
      root.walkBottomUp().forEach(File::delete)
    }
  }

  private fun uninitializedBitmap(): Bitmap {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = unsafeField.get(null)
    return unsafeClass.getMethod("allocateInstance", Class::class.java)
      .invoke(unsafe, Bitmap::class.java) as Bitmap
  }
}
