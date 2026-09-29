package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepthFirstTraversalTest {
  @Test
  fun `first result follows depth first screen order`() {
    val firstLeaf = FakeNode("first leaf")
    val queuedSibling = FakeNode("queued sibling")
    val root = FakeNode("root", FakeNode("first branch", firstLeaf), queuedSibling)
    val visited = mutableListOf<String>()

    val found = DepthFirstTraversal.firstResult(root) { node ->
      node as FakeNode
      visited += node.label
      node.label.takeIf { it == "first leaf" }
    }

    assertEquals("first leaf", found)
    assertEquals(listOf("root", "first branch", "first leaf"), visited)
    assertEquals(0, root.recycleCount)
    assertEquals(1, root.children[0].recycleCount)
    assertEquals(1, firstLeaf.recycleCount)
    assertEquals(1, queuedSibling.recycleCount)
  }

  @Test
  fun `zero result visits the complete tree and recycles every owned child`() {
    val leaf = FakeNode("leaf")
    val branch = FakeNode("branch", leaf)
    val root = FakeNode("root", branch)

    val found = DepthFirstTraversal.firstResult<String>(root) { null }

    assertNull(found)
    assertEquals(0, root.recycleCount)
    assertEquals(1, branch.recycleCount)
    assertEquals(1, leaf.recycleCount)
  }

  @Test
  fun `child lookup failure still recycles the owned branch and queued siblings`() {
    val brokenBranch = FakeNode("broken", FakeNode("unread"), throwOnChildLookup = true)
    val queuedSibling = FakeNode("queued")
    val root = FakeNode("root", brokenBranch, queuedSibling)

    runCatching {
      DepthFirstTraversal.firstResult<String>(root) { null }
    }.onSuccess { error("child lookup should fail") }

    assertEquals(1, brokenBranch.recycleCount)
    assertEquals(1, queuedSibling.recycleCount)
  }

  @Test
  fun `for each visits the complete tree in depth first order and recycles owned children`() {
    val leaf = FakeNode("leaf")
    val branch = FakeNode("branch", leaf)
    val sibling = FakeNode("sibling")
    val root = FakeNode("root", branch, sibling)
    val visited = mutableListOf<String>()

    DepthFirstTraversal.forEach(root) { visited += (it as FakeNode).label }

    assertEquals(listOf("root", "branch", "leaf", "sibling"), visited)
    assertEquals(0, root.recycleCount)
    assertEquals(1, branch.recycleCount)
    assertEquals(1, leaf.recycleCount)
    assertEquals(1, sibling.recycleCount)
  }

  @Test
  fun `for each recycles queued nodes when visitor throws`() {
    val branch = FakeNode("branch")
    val sibling = FakeNode("sibling")
    val root = FakeNode("root", branch, sibling)

    runCatching {
      DepthFirstTraversal.forEach(root) { node ->
        if ((node as FakeNode).label == "branch") error("stop")
      }
    }.onSuccess { error("visitor should fail") }

    assertEquals(1, branch.recycleCount)
    assertEquals(1, sibling.recycleCount)
  }

  private class FakeNode(
    val label: String,
    vararg childNodes: FakeNode,
    private val throwOnChildLookup: Boolean = false,
  ) : TraversalNode {
    val children = childNodes.toList()
    var recycleCount = 0

    override val childCount: Int get() = children.size

    override fun childAt(index: Int): TraversalNode {
      if (throwOnChildLookup) error("unavailable child")
      return children[index]
    }

    override fun recycle() {
      recycleCount += 1
    }
  }
}
