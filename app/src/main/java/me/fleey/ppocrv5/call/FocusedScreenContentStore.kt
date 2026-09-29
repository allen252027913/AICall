package me.fleey.ppocrv5.call

/** The last foreground window observed by the accessibility integration. */
data class FocusedScreenContent(
  val packageName: String,
  val activityName: String?,
  val timestampMillis: Long,
)

object FocusedScreenContentStore {
  private var latest: FocusedScreenContent? = null

  @Synchronized
  fun updateFromActiveRoot(rootPackage: String?, eventPackage: String?, activityName: String?, timestamp: Long) {
    if (rootPackage.isNullOrBlank()) {
      latest = null
      return
    }
    val currentActivity = activityName?.takeIf { eventPackage == rootPackage }
      ?: latest?.takeIf { it.packageName == rootPackage }?.activityName
    update(rootPackage, currentActivity, timestamp)
  }

  @Synchronized
  fun update(packageName: String, activityName: String?, timestamp: Long) {
    require(packageName.isNotBlank()) { "packageName must not be blank" }
    latest = FocusedScreenContent(
      packageName = packageName,
      activityName = activityName?.takeIf(String::isNotBlank),
      timestampMillis = timestamp,
    )
  }

  @Synchronized
  fun snapshot(): FocusedScreenContent? = latest

  @Synchronized
  fun clear() {
    latest = null
  }
}
