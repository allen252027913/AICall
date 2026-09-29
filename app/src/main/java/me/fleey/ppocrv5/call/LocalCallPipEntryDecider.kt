package me.fleey.ppocrv5.call

class LocalCallPipEntryDecider(private val preference: LocalCallPipPreference) {
  fun shouldEnter(callActive: Boolean, screenSharing: Boolean): Boolean =
    LocalCallPipPolicy.shouldEnter(callActive, screenSharing, preference.isEnabled())
}
