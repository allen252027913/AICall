package me.fleey.ppocrv5.call

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.util.UUID

/** Lossless app-private original; survives cache eviction. Caller owns the input bitmap. */
class LongScreenshotStorage(context: Context) {
  private val directory = File(context.applicationContext.filesDir, "wechat_long_screenshots")

  fun saveBitmap(bitmap: Bitmap): File {
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建长截图目录")
    val id = "${System.currentTimeMillis()}_${UUID.randomUUID()}"
    val pending = File(directory, "$id.tmp")
    val target = File(directory, "$id.png")
    try {
      pending.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG 保存失败") }
      if (!pending.renameTo(target)) throw IOException("无法保存长截图原图")
      return target
    } finally { pending.delete() }
  }
}
