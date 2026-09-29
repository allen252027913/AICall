package me.fleey.ppocrv5.call

import android.content.Context

class WeChatCapturePreference internal constructor(
  private val readPersisted: () -> Int?,
  private val writePersisted: (Int) -> Unit,
) {
  constructor(context: Context) : this(
    readPersisted = {
      context.applicationContext.getSharedPreferences("local_call", Context.MODE_PRIVATE)
        .getInt("wechat_capture_count", 10)
    },
    writePersisted = { count ->
      context.applicationContext.getSharedPreferences("local_call", Context.MODE_PRIVATE)
        .edit().putInt("wechat_capture_count", count).apply()
    },
  )

  fun count(): Int = readPersisted()?.takeIf { it in 1..50 } ?: 10

  fun save(input: String): Boolean {
    val count = parse(input) ?: return false
    writePersisted(count)
    return true
  }

  companion object {
    fun parse(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1..50 }
  }
}
