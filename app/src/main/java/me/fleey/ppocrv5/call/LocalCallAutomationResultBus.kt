package me.fleey.ppocrv5.call

data class AccessibilityCommandResult(
  val token: Long,
  val succeeded: Boolean,
  val message: String,
) {
  init {
    require(token > 0L) { "Result token must be positive" }
    require(message.isNotBlank()) { "Result message must not be blank" }
  }
}

class TokenizedAccessibilityResultBus {
  private val subscribers = mutableMapOf<Long, (AccessibilityCommandResult) -> Unit>()
  private val completedTokens = mutableSetOf<Long>()

  @Synchronized
  fun subscribe(token: Long, subscriber: (AccessibilityCommandResult) -> Unit): Boolean {
    if (token <= 0L || token in completedTokens || token in subscribers) return false
    subscribers[token] = subscriber
    return true
  }

  fun publish(result: AccessibilityCommandResult): Boolean {
    val subscriber = synchronized(this) {
      if (result.token in completedTokens) return false
      subscribers.remove(result.token)?.also { completedTokens += result.token }
    } ?: return false
    subscriber(result)
    return true
  }

  @Synchronized
  fun unsubscribe(token: Long): Boolean {
    if (subscribers.remove(token) == null) return false
    completedTokens += token
    return true
  }

  @Synchronized
  fun isActive(token: Long): Boolean = token in subscribers && token !in completedTokens
}

object LocalCallAutomationResultBus {
  private val bus = TokenizedAccessibilityResultBus()

  fun subscribe(token: Long, subscriber: (AccessibilityCommandResult) -> Unit): Boolean =
    bus.subscribe(token, subscriber)

  fun publish(result: AccessibilityCommandResult): Boolean = bus.publish(result)

  fun unsubscribe(token: Long): Boolean = bus.unsubscribe(token)

  fun isActive(token: Long): Boolean = bus.isActive(token)
}
