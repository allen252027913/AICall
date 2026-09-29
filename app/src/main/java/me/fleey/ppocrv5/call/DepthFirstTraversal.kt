package me.fleey.ppocrv5.call

import java.util.ArrayDeque

interface TraversalNode {
  val childCount: Int
  fun childAt(index: Int): TraversalNode?
  fun recycle()
}

object DepthFirstTraversal {
  fun forEach(root: TraversalNode, visit: (TraversalNode) -> Unit) {
    val stack = ArrayDeque<OwnedNode>()
    stack.addLast(OwnedNode(root, owned = false))
    while (stack.isNotEmpty()) {
      val current = stack.removeLast()
      try {
        visit(current.node)
        for (index in current.node.childCount - 1 downTo 0) {
          current.node.childAt(index)?.let { stack.addLast(OwnedNode(it, owned = true)) }
        }
      } catch (error: Throwable) {
        current.recycleIfOwned()
        recycleStack(stack)
        throw error
      }
      current.recycleIfOwned()
    }
  }

  fun <R : Any> firstResult(root: TraversalNode, evaluate: (TraversalNode) -> R?): R? {
    val stack = ArrayDeque<OwnedNode>()
    stack.addLast(OwnedNode(root, owned = false))
    while (stack.isNotEmpty()) {
      val current = stack.removeLast()
      val result = try {
        evaluate(current.node).also { found ->
          if (found == null) {
            for (index in current.node.childCount - 1 downTo 0) {
              current.node.childAt(index)?.let { stack.addLast(OwnedNode(it, owned = true)) }
            }
          }
        }
      } catch (error: Throwable) {
        current.recycleIfOwned()
        recycleStack(stack)
        throw error
      }
      if (result != null) {
        current.recycleIfOwned()
        recycleStack(stack)
        return result
      }
      current.recycleIfOwned()
    }
    return null
  }

  private fun recycleStack(stack: ArrayDeque<OwnedNode>) {
    while (stack.isNotEmpty()) stack.removeLast().recycleIfOwned()
  }

  private fun OwnedNode.recycleIfOwned() {
    if (owned) node.recycle()
  }

  private data class OwnedNode(val node: TraversalNode, val owned: Boolean)
}
