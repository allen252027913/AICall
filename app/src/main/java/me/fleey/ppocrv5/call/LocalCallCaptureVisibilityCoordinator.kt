package me.fleey.ppocrv5.call

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Coordinates screen capture with PiP compositor changes. It has no Android dependencies so the
 * timing and contamination safeguards remain unit-testable.
 */
class LocalCallCaptureVisibilityCoordinator(
  private val pause: (Long) -> Unit = { Thread.sleep(it) },
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val restoreTimeoutMillis: Long = DEFAULT_RESTORE_TIMEOUT_MILLIS,
  private val captureLeaseTimeoutMillis: Long = DEFAULT_CAPTURE_LEASE_TIMEOUT_MILLIS,
  private val beforeLeaseFinalization: () -> Unit = {},
) {
  init {
    require(restoreTimeoutMillis > 0L) { "restoreTimeoutMillis must be positive" }
    require(captureLeaseTimeoutMillis > 0L) { "captureLeaseTimeoutMillis must be positive" }
  }

  interface PipHost {
    fun hidePictureInPictureForCapture(onHidden: () -> Unit)
    fun restorePictureInPictureAfterCapture(onRestored: () -> Unit)
  }

  private val hostLock = Any()
  private var host: PipHost? = null
  private var hostGeneration = 0L
  private var activeCaptureLease: CaptureLease? = null
  private var safeCaptureAtMillis = Long.MIN_VALUE

  fun register(newHost: PipHost) = synchronized(hostLock) {
    host = newHost
    hostGeneration += 1L
    activeCaptureLease?.invalidated = true
  }

  fun unregister(removedHost: PipHost) = synchronized(hostLock) {
    if (host === removedHost) {
      host = null
      hostGeneration += 1L
      activeCaptureLease?.invalidated = true
    }
  }

  fun onPictureInPictureEntered(nowMillis: Long = clock()) {
    delayCaptureUntil(nowMillis + PIP_TRANSITION_SETTLE_MILLIS)
  }

  fun onPictureInPictureAspectRatioUpdated(nowMillis: Long = clock()) {
    delayCaptureUntil(nowMillis + PIP_TRANSITION_SETTLE_MILLIS)
  }

  fun earliestSafeCaptureAtMillis(): Long = synchronized(hostLock) { safeCaptureAtMillis }

  fun <T> captureWithoutPictureInPicture(
    timeoutMillis: Long = DEFAULT_PREPARATION_TIMEOUT_MILLIS,
    settleMillis: Long = DEFAULT_COMPOSITOR_SETTLE_MILLIS,
    prepareCapture: () -> Unit = {},
    onDiscardedCapture: (T) -> Unit = {},
    capture: () -> T,
  ): T {
    waitForActiveCaptureLease()
    waitForSafeCaptureWindow()
    val registration = currentRegistration()
    val currentHost = registration.host
    if (currentHost == null) {
      waitForSafeCaptureWindow()
      requireUnchanged(registration)
      prepareCapture()
      waitForSafeCaptureWindow()
      return captureWithLease(
        acquireCaptureLease(registration),
        host = null,
        onDiscardedCapture,
        capture,
      )
    }
    val hidden = CountDownLatch(1)
    var hideRequested = false
    var captureLeaseAcquired = false
    try {
      currentHost.hidePictureInPictureForCapture { hidden.countDown() }
      hideRequested = true
      if (!hidden.await(timeoutMillis, TimeUnit.MILLISECONDS)) throw PreparationTimeoutException()
      requireUnchanged(registration)
      if (settleMillis > 0L) pause(settleMillis)
      waitForSafeCaptureWindow()
      requireUnchanged(registration)
      prepareCapture()
      waitForSafeCaptureWindow()
      val lease = acquireCaptureLease(registration)
      captureLeaseAcquired = true
      return captureWithLease(lease, currentHost, onDiscardedCapture, capture)
    } finally {
      if (hideRequested && !captureLeaseAcquired) restorePictureInPicture(currentHost)
    }
  }

  private fun delayCaptureUntil(candidateMillis: Long) = synchronized(hostLock) {
    safeCaptureAtMillis = max(safeCaptureAtMillis, candidateMillis)
  }

  private fun currentRegistration() = synchronized(hostLock) { HostRegistration(host, hostGeneration) }

  private fun isUnchanged(registration: HostRegistration): Boolean = synchronized(hostLock) {
    host === registration.host && hostGeneration == registration.generation
  }

  private fun requireUnchanged(registration: HostRegistration) {
    if (!isUnchanged(registration)) throw HostChangedException()
  }

  /** Revalidates registration and reserves a worker-only capture lease. */
  private fun acquireCaptureLease(registration: HostRegistration): CaptureLease {
    while (true) {
      synchronized(hostLock) {
        if (activeCaptureLease == null) {
          requireUnchanged(registration)
          return CaptureLease(registration).also { activeCaptureLease = it }
        }
      }
      waitForActiveCaptureLease()
    }
  }

  private fun <T> captureWithLease(
    lease: CaptureLease,
    host: PipHost?,
    onDiscardedCapture: (T) -> Unit,
    capture: () -> T,
  ): T {
    val captured = runCatching(capture)
    try {
      val validAfterCapture = isLeaseValid(lease)
      val restored = host?.let(::restorePictureInPicture) ?: true
      beforeLeaseFinalization()
      val validAfterRestore = validateAndReleaseCaptureLease(lease)
      captured.exceptionOrNull()?.let { throw it }
      if (!restored) throw RestorationTimeoutException()
      if (!validAfterCapture || !validAfterRestore) throw HostChangedException()
      return captured.getOrThrow()
    } catch (error: Throwable) {
      if (captured.isSuccess) {
        try {
          onDiscardedCapture(captured.getOrThrow())
        } catch (cleanupError: Throwable) {
          if (cleanupError !== error) error.addSuppressed(cleanupError)
        }
      }
      if (error is InterruptedException) Thread.currentThread().interrupt()
      throw error
    } finally {
      releaseCaptureLease(lease)
    }
  }

  private fun restorePictureInPicture(host: PipHost): Boolean {
    val restored = CountDownLatch(1)
    val requested = try {
      host.restorePictureInPictureAfterCapture { restored.countDown() }
      true
    } catch (error: InterruptedException) {
      throw error
    } catch (_: Throwable) {
      false
    }
    return requested && restored.await(restoreTimeoutMillis, TimeUnit.MILLISECONDS)
  }

  private fun waitForActiveCaptureLease() {
    val existingLease = synchronized(hostLock) { activeCaptureLease } ?: return
    if (!existingLease.completed.await(captureLeaseTimeoutMillis, TimeUnit.MILLISECONDS)) {
      throw CaptureLeaseTimeoutException()
    }
  }

  private fun releaseCaptureLease(lease: CaptureLease) = synchronized(hostLock) {
    if (activeCaptureLease === lease) {
      activeCaptureLease = null
      lease.completed.countDown()
    }
  }

  private fun validateAndReleaseCaptureLease(lease: CaptureLease): Boolean = synchronized(hostLock) {
    val valid = activeCaptureLease === lease && !lease.invalidated
    if (activeCaptureLease === lease) {
      activeCaptureLease = null
      lease.completed.countDown()
    }
    valid
  }

  private fun isLeaseValid(lease: CaptureLease): Boolean = synchronized(hostLock) {
    activeCaptureLease === lease && !lease.invalidated
  }

  private fun waitForSafeCaptureWindow() {
    val nowMillis = clock()
    val earliestMillis = earliestSafeCaptureAtMillis()
    if (earliestMillis > nowMillis) pause(earliestMillis - nowMillis)
  }

  class PreparationTimeoutException : IllegalStateException("Timed out waiting for PiP to hide")
  class HostChangedException : IllegalStateException("PiP host changed while preparing screen capture")
  class RestorationTimeoutException : IllegalStateException("Timed out waiting for PiP restoration")
  class CaptureLeaseTimeoutException : IllegalStateException("Timed out waiting for an earlier PiP capture")

  private data class HostRegistration(val host: PipHost?, val generation: Long)
  private class CaptureLease(
    val registration: HostRegistration,
    var invalidated: Boolean = false,
    val completed: CountDownLatch = CountDownLatch(1),
  )

  companion object {
    val shared = LocalCallCaptureVisibilityCoordinator()
    const val DEFAULT_PREPARATION_TIMEOUT_MILLIS = 500L
    const val DEFAULT_COMPOSITOR_SETTLE_MILLIS = 120L
    const val PIP_TRANSITION_SETTLE_MILLIS = 500L
    const val DEFAULT_RESTORE_TIMEOUT_MILLIS = 500L
    const val DEFAULT_CAPTURE_LEASE_TIMEOUT_MILLIS = 1_000L
  }
}
