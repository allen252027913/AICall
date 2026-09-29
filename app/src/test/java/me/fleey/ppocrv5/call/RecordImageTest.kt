package me.fleey.ppocrv5.call

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordImageTest {
  @get:Rule val folder = TemporaryFolder()

  @Test fun `zoom follows fingers and clamps panning to image edges`() {
    val zoom = RecordImageZoom()
    zoom.transform(2f, 0f, 0f, 100f, 100f, 1000f, 1000f, 1000f, 1000f)
    assertEquals(2f, zoom.scale, .001f)
    assertEquals(400f, zoom.x, .001f)
    zoom.transform(1f, 10000f, -10000f, 500f, 500f, 1000f, 1000f, 1000f, 1000f)
    assertEquals(500f, zoom.x, .001f)
    assertEquals(-500f, zoom.y, .001f)
    zoom.transform(.1f, 0f, 0f, 500f, 500f, 1000f, 1000f, 1000f, 1000f)
    assertEquals(1f, zoom.scale, .001f)
    assertEquals(0f, zoom.x, .001f)
    assertEquals(0f, zoom.y, .001f)
  }

  @Test fun `narrow image cannot be panned horizontally until wider than viewport`() {
    val zoom = RecordImageZoom()
    zoom.transform(2f, 500f, 500f, 500f, 500f, 1000f, 1000f, 300f, 1000f)
    assertEquals(0f, zoom.x, .001f)
    assertEquals(500f, zoom.y, .001f)
  }

  @Test fun `gallery copies unchanged source bytes before publishing`() {
    val file = folder.newFile().apply { writeBytes(byteArrayOf(1, 3, 5, 7)) }
    val out = ByteArrayOutputStream()
    var published = false
    val transaction = GalleryCopyTransaction(
      create = { "new-image" }, open = { out },
      publish = { assertArrayEquals(byteArrayOf(1, 3, 5, 7), out.toByteArray()); published = true },
      remove = { error("must not delete successful image") },
    )
    assertEquals("new-image", transaction.copy(file))
    assertTrue(published)
    assertArrayEquals(byteArrayOf(1, 3, 5, 7), file.readBytes())
  }

  @Test fun `failed write deletes only the newly created destination`() {
    val file = folder.newFile().apply { writeText("image") }
    var removed: String? = null
    val transaction = GalleryCopyTransaction(
      create = { "new-image" },
      open = { object : OutputStream() { override fun write(b: Int) { throw IOException("disk full") } } },
      publish = { error("must not publish failed write") }, remove = { removed = it },
    )
    assertThrows(IOException::class.java) { transaction.copy(file) }
    assertEquals("new-image", removed)
    assertTrue(file.exists())
  }

  @Test fun `missing source never creates a gallery item`() {
    val transaction = GalleryCopyTransaction(create = { error("must not insert") }, open = { ByteArrayOutputStream() }, publish = { _: String -> }, remove = { _: String -> })
    assertThrows(IOException::class.java) { transaction.copy(java.io.File(folder.root, "missing.jpg")) }
  }

  @Test fun `failed publish removes destination and preserves original bytes`() {
    val original = byteArrayOf(2, 4, 6, 8)
    val file = folder.newFile().apply { writeBytes(original) }
    val out = ByteArrayOutputStream()
    var removed: String? = null
    val transaction = GalleryCopyTransaction(
      create = { "unpublished-image" }, open = { out },
      publish = { throw IOException("publish failed") }, remove = { removed = it },
    )
    assertThrows(IOException::class.java) { transaction.copy(file) }
    assertEquals("unpublished-image", removed)
    assertArrayEquals(original, out.toByteArray())
    assertArrayEquals(original, file.readBytes())
  }
}
