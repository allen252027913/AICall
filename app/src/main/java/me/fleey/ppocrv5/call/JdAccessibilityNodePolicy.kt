package me.fleey.ppocrv5.call

enum class JdNodeClickStrategy {
  VIEW_ID_THEN_NODE,
  NODE_OR_ANCESTOR,
}

object JdAccessibilityNodePolicy {
  fun matches(
    text: CharSequence?,
    contentDescription: CharSequence?,
    target: JdTarget,
  ): Boolean {
    val expected = normalize(target.label)
    return normalize(text?.toString().orEmpty()) == expected ||
      normalize(contentDescription?.toString().orEmpty()) == expected
  }

  fun clickStrategy(viewId: String?): JdNodeClickStrategy =
    if (!viewId.isNullOrBlank() && viewId.contains(ANDROID_RESOURCE_ID_SEPARATOR)) {
      JdNodeClickStrategy.VIEW_ID_THEN_NODE
    } else {
      JdNodeClickStrategy.NODE_OR_ANCESTOR
    }

  private fun normalize(value: String): String =
    value.lowercase().filterNot(Char::isWhitespace)

  private const val ANDROID_RESOURCE_ID_SEPARATOR = ":id/"
}

object JdNodeClickExecutor {
  fun execute(
    clickDirectNode: () -> Boolean,
    clickAncestorOnly: () -> Boolean,
  ): JdAutomationClickMethod? = when {
    clickDirectNode() -> JdAutomationClickMethod.VIEW_ID
    clickAncestorOnly() -> JdAutomationClickMethod.ACCESSIBILITY_NODE
    else -> null
  }
}
