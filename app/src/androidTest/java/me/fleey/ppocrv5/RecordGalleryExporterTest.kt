package me.fleey.ppocrv5

import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import me.fleey.ppocrv5.call.RecordGalleryExporter
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordGalleryExporterTest {
  @Test @SdkSuppress(minSdkVersion = 29)
  fun savedImageIsPublishedInAlbumWithOriginalBytes() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val source = File.createTempFile("gallery-test-", ".jpg", context.cacheDir)
    val bitmap = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
    source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    bitmap.recycle()
    var saved: Uri? = null
    try {
      val uri = RecordGalleryExporter(context).save(source).also { saved = it }
      assertArrayEquals(source.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
      context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
        assertTrue(it.moveToFirst())
        assertEquals("Pictures/识别记录/", it.getString(0))
        assertEquals(0, it.getInt(1))
      }
      assertTrue(source.exists())
    } finally {
      saved?.let { context.contentResolver.delete(it, null, null) }
      source.delete()
    }
  }
}
