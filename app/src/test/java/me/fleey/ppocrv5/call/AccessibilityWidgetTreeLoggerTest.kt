package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityWidgetTreeLoggerTest {
  @Test
  fun `logger emits text and content for root and every descendant`() {
    val root = FakeWidgetNode(
      className = "Root",
      text = null,
      content = "微信页面",
      children = listOf(
        FakeWidgetNode("TextView", "搜索", null),
        FakeWidgetNode(
          className = "Container",
          text = null,
          content = null,
          children = listOf(FakeWidgetNode("Button", "李佳琦", "搜索按钮")),
        ),
      ),
    )
    val lines = mutableListOf<String>()

    val count = AccessibilityWidgetTreeLogger.log(
      root = root,
      values = { node: TraversalNode -> (node as FakeWidgetNode).logValues() },
      emit = { lines += it },
    )

    assertEquals(4, count)
    assertEquals(
      listOf(
        "node[0] class=Root text=<null> content=\"微信页面\"",
        "node[1] class=TextView text=\"搜索\" content=<null>",
        "node[2] class=Container text=<null> content=<null>",
        "node[3] class=Button text=\"李佳琦\" content=\"搜索按钮\"",
      ),
      lines,
    )
  }

  private class FakeWidgetNode(
    private val className: String?,
    private val text: CharSequence?,
    private val content: CharSequence?,
    private val children: List<FakeWidgetNode> = emptyList(),
  ) : TraversalNode {
    override val childCount: Int get() = children.size

    override fun childAt(index: Int): TraversalNode = children[index]

    override fun recycle() = Unit

    fun logValues() = AccessibilityWidgetLogValues(className, text, content)
  }
}
