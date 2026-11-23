package com.personalai.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class RecorderState {
    UNCONFIGURED,
    IDLE,
    ARMED,
    STARTING,
    ACTIVE_LISTENING,
    SPEECH_DETECTED,
    SILENCE,
    STOPPING,
    DISABLED,
    AUDIO_SILENCED,
    SERVICE_INTERRUPTED,
    STORAGE_FULL,
    PERMISSION_REQUIRED,
    ERROR;

    val isArmedOrCapturing: Boolean
        get() = this == ARMED ||
            this == STARTING ||
            this == ACTIVE_LISTENING ||
            this == SPEECH_DETECTED ||
            this == SILENCE ||
            this == AUDIO_SILENCED

    val isActivelyListening: Boolean
        get() = this == ACTIVE_LISTENING ||
            this == SPEECH_DETECTED ||
            this == SILENCE
}

@Serializable
enum class BleConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATING,
    READY,
    SYNCHRONIZING,
    SYNC_ERROR
}

@Serializable
enum class AudioSourcePreset {
    VOICE_RECOGNITION,
    MIC,
    UNPROCESSED
}
