package me.fleey.ppocrv5.call

import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalCallCaptureHistoryRepositoryTest {
  private lateinit var root: File
  private lateinit var repository: LocalCallCaptureHistoryRepository

  @Before
  fun setUp() {
    root = Files.createTempDirectory("local-call-history").toFile()
    repository = LocalCallCaptureHistoryRepository(root)
  }

  @After
  fun tearDown() {
    root.walkBottomUp().forEach(File::delete)
  }

  @Test
  fun `load returns an empty list for missing or corrupt metadata`() {
    assertTrue(repository.load().isEmpty())
    root.mkdirs()
    File(root, "index.json").writeText("not json")

    assertTrue(repository.load().isEmpty())
  }

  @Test
  fun `provider places history in the private cache directory`() {
    val cacheDirectory = File("build/test-cache")

    assertEquals(
      File(cacheDirectory, "local_call_history"),
      LocalCallCaptureHistoryProvider.directoryIn(cacheDirectory),
    )
  }

  @Test
  fun `missing image files are excluded from loaded history`() {
    root.mkdirs()
    File(root, "index.json").writeText(
      """{"version":1,"nextSequence":2,"entries":[{"id":"10_1","capturedAtMillis":10,"imageFileName":"10_1.jpg","ocrText":"dGV4dA","sourcePackage":"Y29tLmV4YW1wbGU"}]}""",
    )

    assertFalse(repository.load().isNotEmpty())
  }

  @Test
  fun `save keeps only the newest entries and deletes evicted frames`() {
    repository = LocalCallCaptureHistoryRepository(
      rootDirectory = root,
      maxEntries = 3,
      jpegWriter = { _, target -> target.writeBytes(byteArrayOf(1, 2, 3)); true },
    )

    val first = repository.save(uninitializedBitmap(), "first", "com.example.one", 1L)
    repository.save(uninitializedBitmap(), "second", "com.example.two", 2L)
    repository.save(uninitializedBitmap(), "third", "com.example.three", 3L)
    repository.save(uninitializedBitmap(), "fourth", "com.example.four", 4L)

    assertEquals(listOf("fourth", "third", "second"), repository.load().map { it.ocrText })
    assertEquals("com.example.four", repository.load().first().sourcePackage)
    assertFalse(File(root, first.imageFileName).exists())
    assertEquals(3, root.listFiles().orEmpty().count { it.extension == "jpg" })
  }

  @Test
  fun `failed metadata replacement preserves images referenced by the previous index`() {
    val stableRepository = LocalCallCaptureHistoryRepository(
      rootDirectory = root,
      maxEntries = 1,
      jpegWriter = testJpegWriter,
    )
    val first = stableRepository.save(uninitializedBitmap(), "first", "com.example.one", 1L)
    repository = LocalCallCaptureHistoryRepository(
      rootDirectory = root,
      maxEntries = 1,
      jpegWriter = testJpegWriter,
      metadataWriter = { throw IOException("simulated index failure") },
    )

    var failed = false
    try {
      repository.save(uninitializedBitmap(), "second", "com.example.two", 2L)
    } catch (_: IOException) {
      failed = true
    }

    assertTrue(failed)
    assertTrue(File(root, first.imageFileName).isFile)
    assertEquals(listOf("first"), stableRepository.load().map { it.ocrText })
    assertFalse(File(root, "2_2.jpg").exists())
  }

  @Test
  fun `saving after corrupt metadata deletes stale managed frames`() {
    root.mkdirs()
    File(root, "index.json").writeText("corrupt")
    File(root, "100_1.jpg").writeBytes(byteArrayOf(1))
    repository = LocalCallCaptureHistoryRepository(rootDirectory = root, jpegWriter = testJpegWriter)

    repository.save(uninitializedBitmap(), "fresh", "com.example", 200L)

    assertFalse(File(root, "100_1.jpg").exists())
    assertEquals(listOf("fresh"), repository.load().map { it.ocrText })
    assertEquals(1, root.listFiles().orEmpty().count { it.extension == "jpg" })
  }

  @Test
  fun `JPEG compression does not hold the repository monitor needed by readers`() {
    val stableRepository = LocalCallCaptureHistoryRepository(
      rootDirectory = root,
      maxEntries = 1,
      jpegWriter = testJpegWriter,
    )
    val first = stableRepository.save(uninitializedBitmap(), "first", "com.example", 1L)
    val compressionStarted = CountDownLatch(1)
    val allowCompression = CountDownLatch(1)
    repository = LocalCallCaptureHistoryRepository(
      rootDirectory = root,
      maxEntries = 1,
      jpegWriter = { _, target ->
        compressionStarted.countDown()
        check(allowCompression.await(2, TimeUnit.SECONDS))
        target.writeBytes(byteArrayOf(1, 2, 3))
        true
      },
    )
    val saveFinished = CountDownLatch(1)
    val stagedFrame = AtomicReference<StagedCaptureHistoryEntry>()
    Thread {
      stagedFrame.set(repository.stage(uninitializedBitmap(), "frame", "com.example", 10L))
      saveFinished.countDown()
    }.start()

    assertTrue(compressionStarted.await(1, TimeUnit.SECONDS))
    val loadFinished = CountDownLatch(1)
    val loadedIds = AtomicReference<List<String>>()
    Thread {
      loadedIds.set(repository.load().map(CaptureHistoryEntry::id))
      loadFinished.countDown()
    }.start()

    assertTrue("load must not wait for JPEG compression", loadFinished.await(250, TimeUnit.MILLISECONDS))
    assertEquals(listOf(first.id), loadedIds.get())
    assertTrue(File(root, first.imageFileName).isFile)
    allowCompression.countDown()
    assertTrue(saveFinished.await(1, TimeUnit.SECONDS))
    repository.discard(stagedFrame.get())
    assertEquals(listOf(first.id), repository.load().map { it.id })
  }

  @Test
  fun `staged frame is invisible and cannot evict committed history`() {
    repository = LocalCallCaptureHistoryRepository(
      rootDirectory = root,
      maxEntries = 1,
      jpegWriter = testJpegWriter,
    )
    val first = repository.save(uninitializedBitmap(), "first", "com.example", 1L)

    val staged = repository.stage(uninitializedBitmap(), "second", "com.example", 2L)

    assertEquals(listOf(first.id), repository.load().map { it.id })
    assertTrue(File(root, first.imageFileName).isFile)
    assertEquals(1, root.listFiles().orEmpty().count { it.extension == "jpg" })

    repository.discard(staged)

    assertEquals(listOf(first.id), repository.load().map { it.id })
    assertTrue(File(root, first.imageFileName).isFile)
  }

  @Test
  fun `committing a staged frame publishes it atomically and then evicts the oldest`() {
    repository = LocalCallCaptureHistoryRepository(
      rootDirectory = root,
      maxEntries = 1,
      jpegWriter = testJpegWriter,
    )
    val first = repository.save(uninitializedBitmap(), "first", "com.example", 1L)
    val staged = repository.stage(uninitializedBitmap(), "second", "com.example", 2L)

    val prepared = repository.prepareCommit(staged)
    assertEquals(listOf(first.id), repository.load().map { it.id })
    assertTrue(File(root, first.imageFileName).isFile)

    val publication = repository.publish(prepared)
    repository.finishPublication(publication)
    val second = publication.entry

    assertEquals(listOf(second.id), repository.load().map { it.id })
    assertFalse(File(root, first.imageFileName).exists())
    assertTrue(File(root, second.imageFileName).isFile)
  }

  @Test
  fun `rollback of prepared frame preserves visible entry and eviction order`() {
    repository = LocalCallCaptureHistoryRepository(
      rootDirectory = root,
      maxEntries = 1,
      jpegWriter = testJpegWriter,
    )
    val first = repository.save(uninitializedBitmap(), "first", "com.example", 1L)
    val prepared = repository.prepareCommit(
      repository.stage(uninitializedBitmap(), "stale", "com.example", 2L),
    )

    repository.rollback(prepared)

    assertEquals(listOf(first.id), repository.load().map { it.id })
    assertTrue(File(root, first.imageFileName).isFile)
    assertEquals(1, root.listFiles().orEmpty().count { it.extension == "jpg" })
    assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".pending") })
  }

  @Test
  fun `repository startup scavenges abandoned owned pending files without deleting history`() {
    repository = LocalCallCaptureHistoryRepository(rootDirectory = root, jpegWriter = testJpegWriter)
    val first = repository.save(uninitializedBitmap(), "first", "com.example", 1L)
    repository.stage(uninitializedBitmap(), "abandoned", "com.example", 2L)
    assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(".pending") })

    val recovered = LocalCallCaptureHistoryRepository(rootDirectory = root, jpegWriter = testJpegWriter)

    assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".pending") })
    assertEquals(listOf(first.id), recovered.load().map { it.id })
    assertTrue(File(root, first.imageFileName).isFile)
  }

  @Test
  fun `delete removes only the matching stale save`() {
    repository = LocalCallCaptureHistoryRepository(rootDirectory = root, jpegWriter = testJpegWriter)
    val first = repository.save(uninitializedBitmap(), "first", "com.example", 1L)
    val second = repository.save(uninitializedBitmap(), "second", "com.example", 2L)

    assertTrue(repository.delete(first))

    assertFalse(File(root, first.imageFileName).exists())
    assertTrue(File(root, second.imageFileName).isFile)
    assertEquals(listOf(second.id), repository.load().map { it.id })
  }

  @Test
  fun `deleting the last entry persists empty history and cannot delete twice`() {
    repository = LocalCallCaptureHistoryRepository(rootDirectory = root, jpegWriter = testJpegWriter)
    val entry = repository.save(uninitializedBitmap(), "frame", "com.example", 1L)

    assertFalse(repository.delete(entry.copy(imageFileName = "wrong.jpg")))
    assertTrue(File(root, entry.imageFileName).isFile)
    assertTrue(repository.delete(entry))
    assertFalse(repository.delete(entry))
    assertFalse(File(root, entry.imageFileName).exists())
    assertTrue(LocalCallCaptureHistoryRepository(root).load().isEmpty())
  }

  @Test
  fun `delete waits for a prepared save so publication cannot resurrect deleted history`() {
    repository = LocalCallCaptureHistoryRepository(rootDirectory = root, jpegWriter = testJpegWriter)
    val first = repository.save(uninitializedBitmap(), "first", "com.example", 1L)
    val prepared = repository.prepareCommit(
      repository.stage(uninitializedBitmap(), "second", "com.example", 2L),
    )
    val started = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val failure = AtomicReference<Throwable>()
    val deletion = Thread {
      started.countDown()
      try {
        assertTrue(repository.delete(first))
      } catch (error: Throwable) {
        failure.set(error)
      } finally {
        finished.countDown()
      }
    }
    deletion.start()
    try {
      assertTrue(started.await(1, TimeUnit.SECONDS))
      assertFalse("delete must wait for publication", finished.await(100, TimeUnit.MILLISECONDS))
      repository.finishPublication(repository.publish(prepared))
      assertTrue(finished.await(2, TimeUnit.SECONDS))
      failure.get()?.let { throw it }
      assertEquals(listOf("second"), repository.load().map { it.ocrText })
      assertFalse(File(root, first.imageFileName).exists())
      assertTrue(File(root, prepared.entry.imageFileName).isFile)
    } finally {
      if (!prepared.isPublished()) repository.rollback(prepared)
      deletion.join(2_000)
    }
  }

  private val testJpegWriter: (Bitmap, File) -> Boolean = { _, target ->
    target.writeBytes(byteArrayOf(1, 2, 3))
    true
  }

  private fun uninitializedBitmap(): Bitmap {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = unsafeField.get(null)
    return unsafeClass.getMethod("allocateInstance", Class::class.java)
      .invoke(unsafe, Bitmap::class.java) as Bitmap
  }
}
