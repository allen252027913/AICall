package me.fleey.ppocrv5.call

internal class LocalCallAudioEffectsLifecycle {
  private var activeEffects: AutoCloseable? = null

  @Synchronized
  fun retain(effects: AutoCloseable) {
    if (activeEffects === effects) return
    val previousEffects = activeEffects
    activeEffects = effects
    closeQuietly(previousEffects)
  }

  @Synchronized
  fun release(effects: AutoCloseable) {
    if (activeEffects !== effects) return
    activeEffects = null
    closeQuietly(effects)
  }

  @Synchronized
  fun releaseAll() {
    val effects = activeEffects ?: return
    activeEffects = null
    closeQuietly(effects)
  }

  private fun closeQuietly(effects: AutoCloseable?) {
    runCatching { effects?.close() }
  }
}
