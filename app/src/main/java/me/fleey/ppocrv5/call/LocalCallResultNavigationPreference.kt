package me.fleey.ppocrv5.call

import android.content.Context

/** SharedPreferences adapter for the departure baseline that survives Activity recreation/process death. */
class LocalCallResultNavigationPreference(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences(
    PREFERENCE_FILE,
    Context.MODE_PRIVATE,
  )

  fun load(): LocalCallResultNavigationState = LocalCallResultNavigationState.fromPersistedValue(
    LocalCallResultNavigationPersistedValue(
      awaitingExternalReturn = preferences.getBoolean(KEY_AWAITING_EXTERNAL_RETURN, false),
      departureCaptureIds = preferences.getStringSet(KEY_DEPARTURE_CAPTURE_IDS, emptySet()).orEmpty(),
      departureCutoffMillis = preferences.takeIf { it.contains(KEY_DEPARTURE_CUTOFF_MILLIS) }
        ?.getLong(KEY_DEPARTURE_CUTOFF_MILLIS, 0L),
    ),
  )

  fun save(state: LocalCallResultNavigationState) {
    val persisted = state.toPersistedValue()
    val editor = preferences.edit()
      .putBoolean(KEY_AWAITING_EXTERNAL_RETURN, persisted.awaitingExternalReturn)
      .putStringSet(KEY_DEPARTURE_CAPTURE_IDS, persisted.departureCaptureIds)
    persisted.departureCutoffMillis?.let {
      editor.putLong(KEY_DEPARTURE_CUTOFF_MILLIS, it)
    } ?: editor.remove(KEY_DEPARTURE_CUTOFF_MILLIS)
    editor.apply()
  }

  private companion object {
    const val PREFERENCE_FILE = "local_call_result_navigation"
    const val KEY_AWAITING_EXTERNAL_RETURN = "awaiting_external_return"
    const val KEY_DEPARTURE_CAPTURE_IDS = "departure_capture_ids"
    const val KEY_DEPARTURE_CUTOFF_MILLIS = "departure_cutoff_millis"
  }
}
