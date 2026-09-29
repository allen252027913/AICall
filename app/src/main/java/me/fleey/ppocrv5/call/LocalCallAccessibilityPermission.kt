package me.fleey.ppocrv5.call

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

object LocalCallAccessibilityPermission {
  fun isEnabled(context: Context): Boolean {
    val expected = ComponentName(context, LocalCallAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { flattened ->
      ComponentName.unflattenFromString(flattened) == expected
    }
  }

  fun openSettings(context: Context) {
    context.startActivity(
      Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
  }
}
