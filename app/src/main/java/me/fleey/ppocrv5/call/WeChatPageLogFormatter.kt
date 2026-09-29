package me.fleey.ppocrv5.call

object WeChatPageLogFormatter {
  fun pageStart(packageName: String?, pageName: String?): String =
    "page_start package=${formatValue(packageName)} page=${formatValue(pageName)}"

  fun node(
    index: Int,
    className: String?,
    text: CharSequence?,
    contentDescription: CharSequence?,
  ): String =
    "node[$index] class=${formatValue(className)} " +
      "text=${formatComponentValue(text)} content=${formatComponentValue(contentDescription)}"

  fun pageEnd(nodeCount: Int): String = "page_end nodes=$nodeCount"

  private fun formatComponentValue(value: CharSequence?): String =
    value?.toString()?.let { "\"${escape(it)}\"" } ?: "<null>"

  private fun formatValue(value: String?): String = value?.let(::escape) ?: "<null>"

  private fun escape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\r", "\\r")
    .replace("\n", "\\n")
    .replace("\"", "\\\"")
}
