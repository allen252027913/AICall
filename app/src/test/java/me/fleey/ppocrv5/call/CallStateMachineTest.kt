package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {
  @Test
  fun `starts call then enables sharing only after projection grant`() {
    val machine = CallStateMachine()

    machine.reduce(CallEvent.StartCall)
    assertTrue(machine.state.callActive)
    assertFalse(machine.state.screenSharing)
    assertTrue(machine.state.callEpoch > 0L)

    machine.reduce(CallEvent.ScreenShareGranted)
    assertTrue(machine.state.screenSharing)
    assertEquals(CallPhase.LISTENING, machine.state.phase)
  }

  @Test
  fun `projection stop keeps call alive but disables sharing`() {
    val machine = CallStateMachine()
    machine.reduce(CallEvent.StartCall)
    machine.reduce(CallEvent.ScreenShareGranted)
    machine.reduce(CallEvent.ScreenShareStopped)

    assertTrue(machine.state.callActive)
    assertFalse(machine.state.screenSharing)
  }

  @Test
  fun `ending call resets transient state`() {
    val machine = CallStateMachine()
    machine.reduce(CallEvent.StartCall)
    machine.reduce(CallEvent.ScreenShareGranted)
    machine.reduce(CallEvent.TranscriptChanged("看看屏幕"))
    machine.reduce(CallEvent.EndCall)

    assertEquals(CallSessionState(), machine.state)
  }

  @Test
  fun `each new call receives a distinct epoch while idle state exposes none`() {
    val machine = CallStateMachine()
    machine.reduce(CallEvent.StartCall)
    val firstEpoch = machine.state.callEpoch
    machine.reduce(CallEvent.EndCall)

    assertEquals(0L, machine.state.callEpoch)

    machine.reduce(CallEvent.StartCall)

    assertTrue(machine.state.callEpoch > firstEpoch)
  }

  @Test
  fun `current capture identity changes even when OCR text is unchanged`() {
    val machine = CallStateMachine()
    machine.reduce(CallEvent.StartCall)

    machine.reduce(CallEvent.ScreenUnderstood(summary = "同一段 OCR", captureId = "capture-1"))
    assertEquals("capture-1", machine.state.currentCaptureId)

    machine.reduce(CallEvent.ScreenUnderstood(summary = "同一段 OCR", captureId = "capture-2"))
    assertEquals("capture-2", machine.state.currentCaptureId)
    machine.reduce(CallEvent.EndCall)
    assertEquals(null, machine.state.currentCaptureId)
  }

  @Test
  fun `non stop partial transcript is ignored while a reply is speaking`() {
    val machine = CallStateMachine()
    machine.reduce(CallEvent.StartCall)
    machine.reduce(CallEvent.ReplyStarted("正在播报"))

    machine.reduce(CallEvent.TranscriptChanged("我还有一个问题"))

    assertEquals("", machine.state.transcript)
    assertEquals(CallPhase.SPEAKING, machine.state.phase)
  }
}
