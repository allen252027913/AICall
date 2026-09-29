package me.fleey.ppocrv5.call

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.UUID

/** Copies original JPEG/PNG bytes; never exports a scaled preview or changes the private file. */
class RecordGalleryExporter(context: Context) {
  private val resolver = context.applicationContext.contentResolver

  @Suppress("DEPRECATION")
  fun save(source: File, mimeType: String = "image/jpeg", albumName: String = "识别记录"): Uri {
    require(mimeType == "image/jpeg" || mimeType == "image/png")
    require(albumName.isNotBlank() && '/' !in albumName && '\\' !in albumName && albumName != "..")
    if (!source.isFile || source.length() == 0L) throw IOException("图片已失效或被删除")
    val extension = if (mimeType == "image/png") "png" else "jpg"
    val name = "${albumName}_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension"
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, mimeType)
      if (Build.VERSION.SDK_INT >= 29) {
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$albumName")
        put(MediaStore.Images.Media.IS_PENDING, 1)
      } else {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), albumName)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建相册目录")
        put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
      }
    }
    return GalleryCopyTransaction(
      create = {
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
          ?: throw IOException("无法创建相册图片")
      },
      open = { uri -> resolver.openOutputStream(uri, "w") ?: throw IOException("无法写入相册图片") },
      publish = { uri ->
        if (Build.VERSION.SDK_INT >= 29 && resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
          }, null, null) != 1) throw IOException("相册图片发布失败")
      },
      remove = { uri -> resolver.delete(uri, null, null); Unit },
    ).copy(source)
  }
}
