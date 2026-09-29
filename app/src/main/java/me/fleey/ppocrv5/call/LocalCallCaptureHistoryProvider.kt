package me.fleey.ppocrv5.call

import android.content.Context
import java.io.File

object LocalCallCaptureHistoryProvider {
  const val DIRECTORY_NAME = "local_call_history"

  @Volatile private var instance: LocalCallCaptureHistoryRepository? = null

  fun get(context: Context): LocalCallCaptureHistoryRepository = instance ?: synchronized(this) {
    instance ?: LocalCallCaptureHistoryRepository(directoryIn(context.applicationContext.cacheDir))
      .also { instance = it }
  }

  internal fun directoryIn(cacheDirectory: File): File = File(cacheDirectory, DIRECTORY_NAME)
}
