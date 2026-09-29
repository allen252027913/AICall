package me.fleey.ppocrv5.call

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LocalCallAutomationTokenProvider {
  fun nextToken(): Long = PROCESS_SEQUENCE.incrementAndGet().also { token ->
    check(token > 0L) { "Automation token sequence exhausted" }
  }

  private companion object {
    val PROCESS_SEQUENCE = AtomicLong()
  }
}

data class AutomationCommandEnvelope(
  val token: Long,
  val issuedAtMillis: Long,
  val command: AccessibilityCommand,
) {
  init {
    require(token > 0L) { "Command token must be positive" }
  }
}

sealed interface CommandClaim {
  data class Ready(val envelope: AutomationCommandEnvelope) : CommandClaim
  data class Stale(val token: Long) : CommandClaim
}

class TokenizedAccessibilityCommandStore(
  private val maxAgeMillis: Long,
) {
  private var pending: AutomationCommandEnvelope? = null
  private var reservedToken: Long? = null
  private var claimed: AutomationCommandEnvelope? = null
  private var lastAcceptedToken = 0L
  private val cancelledTokens = mutableSetOf<Long>()

  init {
    require(maxAgeMillis > 0L)
  }

  @Synchronized
  fun reserve(token: Long): Boolean {
    if (token <= lastAcceptedToken || token in cancelledTokens) return false
    if (reservedToken != null || pending != null) return false
    reservedToken = token
    lastAcceptedToken = token
    return true
  }

  @Synchronized
  fun enqueue(envelope: AutomationCommandEnvelope): Boolean {
    if (reservedToken != envelope.token || envelope.token in cancelledTokens) return false
    if (pending != null) return false
    reservedToken = null
    pending = envelope
    return true
  }

  @Synchronized
  fun claim(nowMillis: Long): CommandClaim? {
    if (claimed != null) return null
    val envelope = pending ?: return null
    pending = null
    return if (nowMillis - envelope.issuedAtMillis > maxAgeMillis) {
      cancelledTokens += envelope.token
      CommandClaim.Stale(envelope.token)
    } else {
      claimed = envelope
      CommandClaim.Ready(envelope)
    }
  }

  @Synchronized
  fun cancel(token: Long): Boolean {
    if (token <= 0L || token in cancelledTokens) return false
    cancelledTokens += token
    lastAcceptedToken = maxOf(lastAcceptedToken, token)
    val removedReservation = reservedToken == token
    val removedPending = pending?.token == token
    val removedClaim = claimed?.token == token
    if (removedReservation) reservedToken = null
    if (removedPending) pending = null
    if (removedClaim) claimed = null
    return true
  }

  @Synchronized
  fun completeClaim(token: Long): Boolean {
    if (claimed?.token != token) return false
    claimed = null
    return true
  }

  @Synchronized
  fun hasPending(): Boolean = pending != null

  @Synchronized
  fun isActive(token: Long): Boolean =
    token !in cancelledTokens &&
      (reservedToken == token || pending?.token == token || claimed?.token == token)

  @Synchronized
  fun isClaimActive(token: Long): Boolean = claimed?.token == token

  /**
   * Linearizes cancellation with the external side effect. If [cancel] wins first, [action] is
   * never invoked; if this method wins first, the action happened before cancellation.
   */
  @Synchronized
  fun runIfActive(token: Long, action: () -> Boolean): Boolean {
    if (claimed?.token != token) return false
    return action()
  }

  /**
   * A reservation-level lease gate. It is deliberately available before a command is enqueued so
   * worker registration and JD launch can be linearized with cancellation.
   */
  @Synchronized
  fun executeIfActive(token: Long, action: () -> Unit): Boolean {
    if (!isActive(token)) return false
    action()
    return true
  }
}

/** Keeps a JD state transition and its visible feedback behind one cancellation boundary. */
class AccessibilityCommandLeaseGate(
  private val executeIfActive: (Long, () -> Unit) -> Boolean,
) {
  fun transitionAndFeedback(
    token: Long,
    transition: () -> Boolean,
    feedback: () -> Unit,
  ): Boolean {
    var transitioned = false
    val executed = executeIfActive(token) {
      transitioned = transition()
      if (transitioned) feedback()
    }
    return executed && transitioned
  }
}

object LocalCallAutomationCommandStore {
  private val store = TokenizedAccessibilityCommandStore(maxAgeMillis = 5_000L)
  private val onCommandAvailable = AtomicReference<(() -> Unit)?>(null)

  fun reserve(token: Long): Boolean = store.reserve(token)

  fun enqueue(envelope: AutomationCommandEnvelope): Boolean {
    if (!store.enqueue(envelope)) return false
    onCommandAvailable.get()?.invoke()
    return true
  }

  fun claim(nowMillis: Long): CommandClaim? = store.claim(nowMillis)

  fun cancel(token: Long): Boolean {
    if (!store.cancel(token)) return false
    onCommandAvailable.get()?.invoke()
    return true
  }

  fun completeClaim(token: Long): Boolean = store.completeClaim(token)

  fun isActive(token: Long): Boolean = store.isActive(token)

  fun isClaimActive(token: Long): Boolean = store.isClaimActive(token)

  fun runIfActive(token: Long, action: () -> Boolean): Boolean = store.runIfActive(token, action)

  fun executeIfActive(token: Long, action: () -> Unit): Boolean =
    store.executeIfActive(token, action)

  fun setOnCommandAvailable(listener: (() -> Unit)?) {
    onCommandAvailable.set(listener)
    if (listener != null && store.hasPending()) listener.invoke()
  }
}
