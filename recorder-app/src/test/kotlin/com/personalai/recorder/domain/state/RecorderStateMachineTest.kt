package com.personalai.recorder.domain.state

import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.RecorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderStateMachineTest {

    @Test
    fun `bluetooth state changes never alter armed or capturing recorder state`() {
        val sm = RecorderStateMachine()
        sm.dispatch(RecorderEvent.SetupReady)
        sm.dispatch(RecorderEvent.ArmRequested("sess-1"))
        sm.dispatch(RecorderEvent.EngineStarting)
        sm.dispatch(RecorderEvent.ListeningStarted)
        assertEquals(RecorderState.ACTIVE_LISTENING, sm.snapshot.value.recorderState)

        sm.updateBleState(BleConnectionState.CONNECTED)
        sm.updateBleState(BleConnectionState.READY)
        sm.updateBleState(BleConnectionState.DISCONNECTED)
        sm.updateBleState(BleConnectionState.SYNC_ERROR)

        assertEquals(RecorderState.ACTIVE_LISTENING, sm.snapshot.value.recorderState)
        assertTrue(sm.snapshot.value.isCapturingAudio)
        assertEquals(BleConnectionState.SYNC_ERROR, sm.bleState.value)
    }

    @Test
    fun `audio silencing pauses capture and recovers cleanly when unsilenced`() {
        val sm = RecorderStateMachine()
        sm.dispatch(RecorderEvent.SetupReady)
        sm.dispatch(RecorderEvent.ArmRequested("sess-2"))
        sm.dispatch(RecorderEvent.ListeningStarted)
        sm.dispatch(RecorderEvent.SpeechStarted)
        assertEquals(RecorderState.SPEECH_DETECTED, sm.snapshot.value.recorderState)

        sm.dispatch(RecorderEvent.AudioSilenced("Cellular call"))
        assertEquals(RecorderState.AUDIO_SILENCED, sm.snapshot.value.recorderState)
        assertFalse(sm.snapshot.value.isCapturingAudio)

        sm.dispatch(RecorderEvent.AudioUnsilenced)
        assertEquals(RecorderState.ACTIVE_LISTENING, sm.snapshot.value.recorderState)
        assertTrue(sm.snapshot.value.isCapturingAudio)
    }

    @Test
    fun `privacy kill switch forces DISABLED and blocks arming`() {
        val sm = RecorderStateMachine()
        sm.dispatch(RecorderEvent.SetupReady)
        sm.dispatch(RecorderEvent.PrivacyDisabledChanged(true))
        assertEquals(RecorderState.DISABLED, sm.snapshot.value.recorderState)

        sm.dispatch(RecorderEvent.ArmRequested("sess-blocked"))
        assertEquals(RecorderState.DISABLED, sm.snapshot.value.recorderState)

        sm.dispatch(RecorderEvent.PrivacyDisabledChanged(false))
        assertEquals(RecorderState.IDLE, sm.snapshot.value.recorderState)
    }
}
