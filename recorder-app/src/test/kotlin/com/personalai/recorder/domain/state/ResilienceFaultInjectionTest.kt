package com.personalai.recorder.domain.state

import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.RecorderConfigPayload
import com.personalai.protocol.RecorderState
import com.personalai.recorder.audio.SegmentCloseReason
import com.personalai.recorder.audio.SpeechSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilienceFaultInjectionTest {

    @Test
    fun `ble disconnect during active speech preserves recording and seals segment locally`() {
        val sm = RecorderStateMachine()
        sm.dispatch(RecorderEvent.SetupReady)
        sm.dispatch(RecorderEvent.ArmRequested("sess-outage"))
        sm.dispatch(RecorderEvent.EngineStarting)
        sm.dispatch(RecorderEvent.ListeningStarted)
        sm.updateBleState(BleConnectionState.READY)

        val segmenter = SpeechSegmenter(
            sessionId = "sess-outage",
            config = RecorderConfigPayload(minSpeechDurationMs = 256, silenceTimeoutMs = 640)
        )

        var ts = 50_000L
        repeat(10) {
            segmenter.onFrame(ShortArray(512) { 100 }, 0.9f, ts)
            ts += 32L
        }
        sm.dispatch(RecorderEvent.SpeechStarted)

        // Simulate abrupt BLE disconnect mid-utterance.
        sm.updateBleState(BleConnectionState.DISCONNECTED)
        assertEquals(RecorderState.SPEECH_DETECTED, sm.snapshot.value.recorderState)

        // Continue speech & trailing silence while BLE remains disconnected.
        var sealed = segmenter.onFrame(ShortArray(512) { 100 }, 0.9f, ts).completedSegment
        repeat(30) {
            ts += 32L
            val res = segmenter.onFrame(ShortArray(512), 0.02f, ts)
            if (res.completedSegment != null) sealed = res.completedSegment
        }

        assertNotNull("Expected locally saved speech segment despite BLE disconnect", sealed)
        assertEquals(SegmentCloseReason.SILENCE_TIMEOUT, sealed!!.closeReason)
        assertEquals(BleConnectionState.DISCONNECTED, sm.bleState.value)
        assertTrue(sm.snapshot.value.recorderState.isArmedOrCapturing)
    }

    @Test
    fun `storage full and service interruption states clear active capture accurately`() {
        val sm = RecorderStateMachine()
        sm.dispatch(RecorderEvent.SetupReady)
        sm.dispatch(RecorderEvent.ArmRequested("sess-full"))
        sm.dispatch(RecorderEvent.ListeningStarted)

        sm.dispatch(RecorderEvent.StorageExhausted)
        assertEquals(RecorderState.STORAGE_FULL, sm.snapshot.value.recorderState)
        assertEquals(false, sm.snapshot.value.isCapturingAudio)

        sm.dispatch(RecorderEvent.ServiceInterruptedDetected("OS killed background process"))
        assertEquals(RecorderState.SERVICE_INTERRUPTED, sm.snapshot.value.recorderState)
        assertEquals(null, sm.snapshot.value.activeSessionId)

        sm.dispatch(RecorderEvent.AcknowledgeNonFatalState)
        assertEquals(RecorderState.IDLE, sm.snapshot.value.recorderState)
    }

    @Test
    fun `standby pause and resume preserves armed session without exiting service`() {
        val sm = RecorderStateMachine()
        sm.dispatch(RecorderEvent.SetupReady)
        sm.dispatch(RecorderEvent.ArmRequested("sess-standby"))
        sm.dispatch(RecorderEvent.ListeningStarted)

        sm.dispatch(RecorderEvent.StandbyPaused)
        assertEquals(RecorderState.ARMED, sm.snapshot.value.recorderState)
        assertEquals("sess-standby", sm.snapshot.value.activeSessionId)
        assertEquals(false, sm.snapshot.value.isCapturingAudio)

        sm.dispatch(RecorderEvent.EngineStarting)
        sm.dispatch(RecorderEvent.ListeningStarted)
        assertEquals(RecorderState.ACTIVE_LISTENING, sm.snapshot.value.recorderState)
        assertEquals(true, sm.snapshot.value.isCapturingAudio)
    }
}
