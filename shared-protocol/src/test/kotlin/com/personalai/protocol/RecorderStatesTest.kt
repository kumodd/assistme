package com.personalai.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderStatesTest {
    @Test
    fun `armed and capture states report correct flags`() {
        assertTrue(RecorderState.ARMED.isArmedOrCapturing)
        assertTrue(RecorderState.ACTIVE_LISTENING.isArmedOrCapturing)
        assertTrue(RecorderState.SPEECH_DETECTED.isActivelyListening)
        assertFalse(RecorderState.IDLE.isArmedOrCapturing)
        assertFalse(RecorderState.SERVICE_INTERRUPTED.isArmedOrCapturing)
        assertFalse(RecorderState.AUDIO_SILENCED.isActivelyListening)
        assertTrue(RecorderState.AUDIO_SILENCED.isArmedOrCapturing)
    }
}
