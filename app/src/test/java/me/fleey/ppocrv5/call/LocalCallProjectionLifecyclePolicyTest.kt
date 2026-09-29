package me.fleey.ppocrv5.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallProjectionLifecyclePolicyTest {
  @Test
  fun `projection result is accepted only for the active call that requested it`() {
    assertTrue(
      LocalCallProjectionLifecyclePolicy.acceptAuthorizationResult(
        requestedCallEpoch = 7L,
        currentCallEpoch = 7L,
        callActive = true,
      ),
    )
    assertFalse(
      LocalCallProjectionLifecyclePolicy.acceptAuthorizationResult(
        requestedCallEpoch = 7L,
        currentCallEpoch = 0L,
        callActive = false,
      ),
    )
    assertFalse(
      LocalCallProjectionLifecyclePolicy.acceptAuthorizationResult(
        requestedCallEpoch = 7L,
        currentCallEpoch = 8L,
        callActive = true,
      ),
    )
  }

  @Test
  fun `missing request epoch cannot affect current call`() {
    assertFalse(
      LocalCallProjectionLifecyclePolicy.acceptAuthorizationResult(
        requestedCallEpoch = null,
        currentCallEpoch = 3L,
        callActive = true,
      ),
    )
  }

  @Test
  fun `revocation is surfaced only while its call remains active`() {
    assertTrue(
      LocalCallProjectionLifecyclePolicy.shouldSurfaceRevocation(
        projectionCallEpoch = 4L,
        currentCallEpoch = 4L,
        callActive = true,
      ),
    )
    assertFalse(
      LocalCallProjectionLifecyclePolicy.shouldSurfaceRevocation(
        projectionCallEpoch = 4L,
        currentCallEpoch = 0L,
        callActive = false,
      ),
    )
    assertFalse(
      LocalCallProjectionLifecyclePolicy.shouldSurfaceRevocation(
        projectionCallEpoch = 4L,
        currentCallEpoch = 5L,
        callActive = true,
      ),
    )
  }

  @Test
  fun `idle screen share start action must stop its service instance`() {
    assertTrue(LocalCallProjectionLifecyclePolicy.shouldStopIdleService(callActive = false))
    assertFalse(LocalCallProjectionLifecyclePolicy.shouldStopIdleService(callActive = true))
  }
}
