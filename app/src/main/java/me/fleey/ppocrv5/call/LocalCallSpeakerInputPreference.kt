package me.fleey.ppocrv5.call

import android.content.Context

class LocalCallSpeakerInputPreference internal constructor(
  private val readPersisted: () -> Boolean?,
  private val writePersisted: (Boolean) -> Unit,
) {
  constructor(context: Context) : this(
    readPersisted = {
      val preferences = context.applicationContext.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
      if (preferences.contains(KEY_ENABLED)) preferences.getBoolean(KEY_ENABLED, DEFAULT_ENABLED) else null
    },
    writePersisted = { enabled ->
      context.applicationContext
        .getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ENABLED, enabled)
        .apply()
    },
  )

  fun isEnabled(): Boolean = readPersisted() ?: DEFAULT_ENABLED

  fun inputMode(): LocalCallAudioInputMode = if (isEnabled()) {
    LocalCallAudioInputMode.INCLUDE_SPEAKER_PLAYBACK
  } else {
    LocalCallAudioInputMode.USER_VOICE_ONLY
  }

  fun setEnabled(enabled: Boolean) {
    writePersisted(enabled)
  }

  private companion object {
    const val PREFERENCE_FILE = "local_call"
    const val KEY_ENABLED = "speaker_playback_input_enabled"
    const val DEFAULT_ENABLED = false
  }
}
