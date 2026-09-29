package me.fleey.ppocrv5.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallPipPreferenceTest {
  @Test
  fun `picture in picture defaults to enabled`() {
    val preference = LocalCallPipPreference(readPersisted = { null }, writePersisted = { })

    assertTrue(preference.isEnabled())
  }

  @Test
  fun `persisted switch controls picture in picture entry`() {
    var persisted: Boolean? = false
    val preference = LocalCallPipPreference(
      readPersisted = { persisted },
      writePersisted = { persisted = it },
    )
    val entryDecider = LocalCallPipEntryDecider(preference)

    assertFalse(entryDecider.shouldEnter(callActive = true, screenSharing = true))
    preference.setEnabled(true)
    assertTrue(entryDecider.shouldEnter(callActive = true, screenSharing = true))
  }
}
