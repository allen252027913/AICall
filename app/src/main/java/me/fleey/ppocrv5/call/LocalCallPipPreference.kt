package me.fleey.ppocrv5.call

import android.content.Context

class LocalCallPipPreference internal constructor(
  private val readPersisted: () -> Boolean?,
  private val writePersisted: (Boolean) -> Unit,
) {
  constructor(context: Context) : this(
    readPersisted = {
      val preferences = context.applicationContext.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
      if (preferences.contains(KEY_ENABLED)) preferences.getBoolean(KEY_ENABLED, DEFAULT_ENABLED) else null
    },
    writePersisted = { enabled ->
      context.applicationContext.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ENABLED, enabled)
        .apply()
    },
  )

  fun isEnabled(): Boolean = readPersisted() ?: DEFAULT_ENABLED

  fun setEnabled(enabled: Boolean) {
    writePersisted(enabled)
  }

  private companion object {
    const val PREFERENCE_FILE = "local_call"
    const val KEY_ENABLED = "picture_in_picture_enabled"
    const val DEFAULT_ENABLED = true
  }
}
