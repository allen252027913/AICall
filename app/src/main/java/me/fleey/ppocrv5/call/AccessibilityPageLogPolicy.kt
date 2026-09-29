package me.fleey.ppocrv5.call

data class AccessibilityPageLogRequest(
  val packageName: String?,
  val pageName: String?,
)

object AccessibilityPageLogPolicy {
  fun requestFor(
    eventType: Int,
    windowStateChangedEventType: Int,
    packageName: String?,
    pageName: String?,
  ): AccessibilityPageLogRequest? {
    if (eventType != windowStateChangedEventType) return null
    return AccessibilityPageLogRequest(packageName, pageName)
  }
}
