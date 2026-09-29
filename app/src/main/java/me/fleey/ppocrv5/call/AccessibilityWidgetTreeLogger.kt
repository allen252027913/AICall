package me.fleey.ppocrv5.call

data class AccessibilityWidgetLogValues(
  val className: String?,
  val text: CharSequence?,
  val contentDescription: CharSequence?,
)

object AccessibilityWidgetTreeLogger {
  fun log(
    root: TraversalNode,
    values: (TraversalNode) -> AccessibilityWidgetLogValues,
    emit: (String) -> Unit,
  ): Int {
    var nodeCount = 0
    DepthFirstTraversal.forEach(root) { node ->
      val nodeValues = values(node)
      emit(
        WeChatPageLogFormatter.node(
          index = nodeCount,
          className = nodeValues.className,
          text = nodeValues.text,
          contentDescription = nodeValues.contentDescription,
        ),
      )
      nodeCount += 1
    }
    return nodeCount
  }
}
