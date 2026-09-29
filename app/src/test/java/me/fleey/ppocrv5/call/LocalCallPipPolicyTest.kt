package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallPipPolicyTest {
  @Test
  fun `enters only while the enabled active call is sharing its screen`() {
    assertTrue(LocalCallPipPolicy.shouldEnter(callActive = true, screenSharing = true, userEnabled = true))
    assertFalse(LocalCallPipPolicy.shouldEnter(callActive = false, screenSharing = true, userEnabled = true))
    assertFalse(LocalCallPipPolicy.shouldEnter(callActive = true, screenSharing = false, userEnabled = true))
    assertFalse(LocalCallPipPolicy.shouldEnter(callActive = true, screenSharing = true, userEnabled = false))
  }

  @Test
  fun `uses a compact text-only ratio before a frame and captured portrait ratio afterwards`() {
    val compact = LocalCallPipPolicy.aspectRatioDimensions(PipContentMode.TEXT_ONLY, null, null)
    val frame = LocalCallPipPolicy.aspectRatioDimensions(PipContentMode.FRAME_WITH_TEXT, 1080, 2400)

    assertEquals(PipAspectRatio(1, 1), compact)
    assertEquals(PipAspectRatio(1080, 2400), frame)
    assertTrue(frame.width.toFloat() / frame.height < compact.width.toFloat() / compact.height)
  }

  @Test
  fun `uses text-only until the first usable frame arrives`() {
    assertEquals(PipContentMode.TEXT_ONLY, LocalCallPipPolicy.contentMode(hasFrame = false))
    assertEquals(PipContentMode.FRAME_WITH_TEXT, LocalCallPipPolicy.contentMode(hasFrame = true))
  }

  @Test
  fun `old history stays text-only until this call publishes a capture identity`() {
    assertEquals(
      PipContentMode.TEXT_ONLY,
      LocalCallPipPresentationPolicy.contentMode(currentCaptureId = null, hasCurrentCaptureFile = true),
    )
    assertEquals(
      PipContentMode.FRAME_WITH_TEXT,
      LocalCallPipPresentationPolicy.contentMode(currentCaptureId = "new-capture", hasCurrentCaptureFile = true),
    )
  }

  @Test
  fun `pip presentation wins over results routing`() {
    assertEquals(
      LocalCallPresentation.PIP,
      LocalCallPipPresentationPolicy.route(isInPictureInPicture = true, showingResults = true),
    )
    assertEquals(
      LocalCallPresentation.RESULTS,
      LocalCallPipPresentationPolicy.route(isInPictureInPicture = false, showingResults = true),
    )
  }

  @Test
  fun `failed parameter update does not schedule a capture settle delay`() {
    assertFalse(LocalCallPipParameterUpdatePolicy.shouldNotifyAspectRatioUpdated(paramsApplied = false))
    assertTrue(LocalCallPipParameterUpdatePolicy.shouldNotifyAspectRatioUpdated(paramsApplied = true))
  }

  @Test
  fun `uses waiting and empty OCR copy appropriate for the available content`() {
    assertEquals("等待你询问当前页面", LocalCallPipPolicy.previewText(hasFrame = false, ocrText = ""))
    assertEquals("当前画面未识别到文字", LocalCallPipPolicy.previewText(hasFrame = true, ocrText = "  "))
    assertEquals("商品名称 99元", LocalCallPipPolicy.previewText(hasFrame = true, ocrText = "商品名称 99元"))
  }

  @Test
  fun `ongoing pip finishes when call or projection ends and otherwise keeps content`() {
    assertEquals(
      OngoingPipAction.KEEP_SHOWING,
      LocalCallOngoingPipPolicy.action(
        isInPictureInPicture = true,
        callActive = true,
        screenSharing = true,
      ),
    )
    assertEquals(
      OngoingPipAction.FINISH_ACTIVITY,
      LocalCallOngoingPipPolicy.action(
        isInPictureInPicture = true,
        callActive = false,
        screenSharing = true,
      ),
    )
    assertEquals(
      OngoingPipAction.FINISH_ACTIVITY,
      LocalCallOngoingPipPolicy.action(
        isInPictureInPicture = true,
        callActive = true,
        screenSharing = false,
      ),
    )
    assertEquals(
      OngoingPipAction.NONE,
      LocalCallOngoingPipPolicy.action(
        isInPictureInPicture = false,
        callActive = false,
        screenSharing = false,
      ),
    )
  }
}
