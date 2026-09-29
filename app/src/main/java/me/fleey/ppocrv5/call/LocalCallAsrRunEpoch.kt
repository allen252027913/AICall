package me.fleey.ppocrv5.call

import java.util.concurrent.atomic.AtomicLong

/** Identifies one concrete SpeechEngine.start run independently from spoken-turn cancellation. */
class LocalCallAsrRunEpoch {
  private val current = AtomicLong()

  fun beginRun(): Long = current.incrementAndGet()

  fun invalidateRun(): Long = current.incrementAndGet()

  fun isCurrent(callbackEpoch: Long, callActive: Boolean): Boolean =
    callActive && callbackEpoch == current.get()
}
