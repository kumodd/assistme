package com.personalai.recorder.domain.state

import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.RecorderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class RecorderSnapshot(
    val recorderState: RecorderState = RecorderState.UNCONFIGURED,
    val bleState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val activeSessionId: String? = null,
    val isCapturingAudio: Boolean = false,
    val audioLevelDb: Float = -90f,
    val vadProbability: Float = 0f,
    val inputDeviceName: String = "Default Mic",
    val activeSegmentDurationMs: Long = 0L,
    val sessionSegmentsCount: Int = 0,
    val interruptionReason: String? = null,
    val lastError: String? = null,
    val privacyKillSwitch: Boolean = false
)

sealed interface RecorderEvent {
    data object SetupIncomplete : RecorderEvent
    data object PermissionsMissing : RecorderEvent
    data object SetupReady : RecorderEvent
    data class ArmRequested(val sessionId: String) : RecorderEvent
    data object EngineStarting : RecorderEvent
    data object ListeningStarted : RecorderEvent
    data object SpeechStarted : RecorderEvent
    data object TrailingSilenceStarted : RecorderEvent
    data object SegmentSealedBackToListening : RecorderEvent
    data object StopRequested : RecorderEvent
    data object StandbyPaused : RecorderEvent
    data object StoppedToIdle : RecorderEvent
    data class AudioSilenced(val reason: String) : RecorderEvent
    data object AudioUnsilenced : RecorderEvent
    data class ServiceInterruptedDetected(val reason: String) : RecorderEvent
    data object StorageExhausted : RecorderEvent
    data class FatalError(val message: String) : RecorderEvent
    data class PrivacyDisabledChanged(val disabled: Boolean) : RecorderEvent
    data object AcknowledgeNonFatalState : RecorderEvent
}

@Singleton
class RecorderStateMachine @Inject constructor() {

    private val _snapshot = MutableStateFlow(RecorderSnapshot())
    val snapshot: StateFlow<RecorderSnapshot> = _snapshot.asStateFlow()

    // Bluetooth state is strictly independent and never changes recorderState.
    private val _bleState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val bleState: StateFlow<BleConnectionState> = _bleState.asStateFlow()

    private val lock = Any()

    fun updateBleState(newState: BleConnectionState) = synchronized(lock) {
        _bleState.value = newState
        _snapshot.update { it.copy(bleState = newState) }
    }

    fun updateTelemetry(
        audioLevelDb: Float,
        vadProbability: Float,
        inputDeviceName: String,
        activeSegmentDurationMs: Long? = null
    ) {
        _snapshot.update { current ->
            current.copy(
                audioLevelDb = audioLevelDb,
                vadProbability = vadProbability,
                inputDeviceName = inputDeviceName,
                activeSegmentDurationMs = activeSegmentDurationMs ?: current.activeSegmentDurationMs
            )
        }
    }

    fun incrementSessionSegments() {
        _snapshot.update { it.copy(sessionSegmentsCount = it.sessionSegmentsCount + 1) }
    }

    fun dispatch(event: RecorderEvent): RecorderState = synchronized(lock) {
        val current = _snapshot.value
        val next = reduce(current, event)
        _snapshot.value = next
        next.recorderState
    }

    private fun reduce(current: RecorderSnapshot, event: RecorderEvent): RecorderSnapshot {
        return when (event) {
            is RecorderEvent.PrivacyDisabledChanged -> {
                if (event.disabled) {
                    current.copy(
                        recorderState = RecorderState.DISABLED,
                        isCapturingAudio = false,
                        activeSessionId = null,
                        privacyKillSwitch = true,
                        interruptionReason = "Privacy kill-switch enabled"
                    )
                } else {
                    val restored = if (current.recorderState == RecorderState.DISABLED) {
                        RecorderState.IDLE
                    } else {
                        current.recorderState
                    }
                    current.copy(
                        recorderState = restored,
                        privacyKillSwitch = false,
                        interruptionReason = null
                    )
                }
            }

            RecorderEvent.SetupIncomplete -> current.copy(
                recorderState = RecorderState.UNCONFIGURED,
                isCapturingAudio = false
            )

            RecorderEvent.PermissionsMissing -> current.copy(
                recorderState = RecorderState.PERMISSION_REQUIRED,
                isCapturingAudio = false,
                activeSessionId = null,
                interruptionReason = "Required permissions missing or revoked"
            )

            RecorderEvent.SetupReady -> {
                if (current.privacyKillSwitch) {
                    current.copy(recorderState = RecorderState.DISABLED)
                } else if (
                    current.recorderState == RecorderState.UNCONFIGURED ||
                    current.recorderState == RecorderState.PERMISSION_REQUIRED
                ) {
                    current.copy(
                        recorderState = RecorderState.IDLE,
                        interruptionReason = null,
                        lastError = null
                    )
                } else {
                    current
                }
            }

            is RecorderEvent.ArmRequested -> {
                if (current.privacyKillSwitch) {
                    current.copy(recorderState = RecorderState.DISABLED)
                } else {
                    current.copy(
                        recorderState = RecorderState.ARMED,
                        activeSessionId = event.sessionId,
                        sessionSegmentsCount = 0,
                        activeSegmentDurationMs = 0L,
                        interruptionReason = null,
                        lastError = null
                    )
                }
            }

            RecorderEvent.EngineStarting -> current.copy(
                recorderState = RecorderState.STARTING
            )

            RecorderEvent.ListeningStarted -> current.copy(
                recorderState = RecorderState.ACTIVE_LISTENING,
                isCapturingAudio = true,
                interruptionReason = null
            )

            RecorderEvent.SpeechStarted -> {
                if (current.recorderState.isArmedOrCapturing) {
                    current.copy(recorderState = RecorderState.SPEECH_DETECTED)
                } else {
                    current
                }
            }

            RecorderEvent.TrailingSilenceStarted -> {
                if (current.recorderState.isArmedOrCapturing) {
                    current.copy(recorderState = RecorderState.SILENCE)
                } else {
                    current
                }
            }

            RecorderEvent.SegmentSealedBackToListening -> {
                if (current.recorderState.isArmedOrCapturing) {
                    current.copy(
                        recorderState = RecorderState.ACTIVE_LISTENING,
                        activeSegmentDurationMs = 0L
                    )
                } else {
                    current
                }
            }

            RecorderEvent.StopRequested -> current.copy(
                recorderState = RecorderState.STOPPING,
                isCapturingAudio = false
            )

            RecorderEvent.StandbyPaused -> {
                if (current.recorderState.isArmedOrCapturing) {
                    current.copy(
                        recorderState = RecorderState.ARMED,
                        isCapturingAudio = false,
                        activeSegmentDurationMs = 0L,
                        audioLevelDb = -90f,
                        vadProbability = 0f,
                        interruptionReason = "Paused to armed standby"
                    )
                } else {
                    current
                }
            }

            RecorderEvent.StoppedToIdle -> current.copy(
                recorderState = if (current.privacyKillSwitch) RecorderState.DISABLED else RecorderState.IDLE,
                isCapturingAudio = false,
                activeSessionId = null,
                activeSegmentDurationMs = 0L,
                audioLevelDb = -90f,
                vadProbability = 0f
            )

            is RecorderEvent.AudioSilenced -> current.copy(
                recorderState = RecorderState.AUDIO_SILENCED,
                isCapturingAudio = false,
                interruptionReason = event.reason,
                activeSegmentDurationMs = 0L
            )

            RecorderEvent.AudioUnsilenced -> {
                if (current.recorderState == RecorderState.AUDIO_SILENCED) {
                    current.copy(
                        recorderState = RecorderState.ACTIVE_LISTENING,
                        isCapturingAudio = true,
                        interruptionReason = null
                    )
                } else {
                    current
                }
            }

            is RecorderEvent.ServiceInterruptedDetected -> current.copy(
                recorderState = RecorderState.SERVICE_INTERRUPTED,
                isCapturingAudio = false,
                activeSessionId = null,
                interruptionReason = event.reason
            )

            RecorderEvent.StorageExhausted -> current.copy(
                recorderState = RecorderState.STORAGE_FULL,
                isCapturingAudio = false,
                activeSessionId = null,
                interruptionReason = "Storage floor reached; capture stopped safely"
            )

            is RecorderEvent.FatalError -> current.copy(
                recorderState = RecorderState.ERROR,
                isCapturingAudio = false,
                activeSessionId = null,
                lastError = event.message
            )

            RecorderEvent.AcknowledgeNonFatalState -> {
                val target = if (current.privacyKillSwitch) RecorderState.DISABLED else RecorderState.IDLE
                current.copy(
                    recorderState = target,
                    interruptionReason = null,
                    lastError = null
                )
            }
        }
    }
}
