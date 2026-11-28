package com.personalai.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class CommandType(val opCode: Byte) {
    START_RECORDING(0x01),
    STOP_RECORDING(0x02),
    GET_STATUS(0x03),
    GET_BATTERY(0x04),
    GET_STORAGE(0x05),
    GET_DEVICE_INFO(0x06),
    SYNC_DATA(0x07),
    SET_CONFIG(0x08),
    PING(0x09),
    SYNC_ACK(0x0A),
    FETCH_SEGMENT_CHUNK(0x0B),
    AUTH_VERIFY(0x10),
    PAIR_EXCHANGE(0x11);

    companion object {
        fun fromOpCode(code: Byte): CommandType? = entries.firstOrNull { it.opCode == code }
    }
}

@Serializable
enum class BleMessageType(val code: Byte) {
    COMMAND(0x01),
    ACK(0x02),
    STATUS_EVENT(0x03),
    CONFIG(0x04),
    SYNC_CTRL(0x05),
    SYNC_DATA(0x06);

    companion object {
        fun fromCode(code: Byte): BleMessageType? = entries.firstOrNull { it.code == code }
    }
}

@Serializable
enum class StatusEventType(val opCode: Byte) {
    RECORDER_STATUS(0x21),
    AUTH_CHALLENGE(0x22),
    BATTERY_INFO(0x23),
    STORAGE_INFO(0x24),
    SYNC_MANIFEST(0x25);

    companion object {
        fun fromOpCode(code: Byte): StatusEventType? = entries.firstOrNull { it.opCode == code }
    }
}

@Serializable
enum class StopMode {
    STOP_SERVICE,
    STANDBY_PAUSE
}

@Serializable
enum class ProtocolErrorCode {
    NONE,
    SERVICE_NOT_RUNNING,
    USER_INTERACTION_REQUIRED,
    PERMISSION_MISSING,
    AUTH_FAILED,
    REPLAY_DETECTED,
    EXPIRED,
    STORAGE_FULL,
    BUSY,
    INVALID_ARGUMENT,
    UNSUPPORTED_VERSION,
    INTERNAL_ERROR
}

@Serializable
data class RecorderConfigPayload(
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    val vadThreshold: Float = 0.50f,
    val preRollMs: Int = 640,
    val silenceTimeoutMs: Int = 1_600,
    val maxSegmentDurationMs: Int = 45_000,
    val minSpeechDurationMs: Int = 800,
    val audioSource: AudioSourcePreset = AudioSourcePreset.VOICE_RECOGNITION,
    val minFreeStorageMb: Long = 250L,
    val minBatteryPctToRecord: Int = 8,
    val localOnlyPauseSync: Boolean = false
)

@Serializable
data class RecorderStatusPayload(
    val recorderState: RecorderState,
    val activeSessionId: String?,
    val batteryPct: Int,
    val isCharging: Boolean,
    val thermalStatus: Int,
    val freeStorageBytes: Long,
    val totalStorageBytes: Long,
    val pendingSyncSegments: Int,
    val currentSessionSegments: Int,
    val interruptionReason: String? = null,
    val localOnlyPauseSync: Boolean = false,
    val lastSyncTimestampMs: Long? = null,
    val timestampMs: Long
)

@Serializable
data class BatteryInfoPayload(
    val batteryPct: Int,
    val isCharging: Boolean,
    val isPowerSaveMode: Boolean,
    val thermalStatus: Int,
    val timestampMs: Long
)

@Serializable
data class StorageInfoPayload(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBySegmentsBytes: Long,
    val pendingSyncSegments: Int,
    val totalSegments: Int
)

@Serializable
data class DeviceInfoPayload(
    val deviceId: String,
    val deviceName: String,
    val firmwareVersion: String,
    val protocolVersion: Int,
    val pairedDeviceId: String?,
    val l2capSupported: Boolean,
    val maxMtuHint: Int,
    val manufacturer: String,
    val model: String,
    val androidSdk: Int
)

@Serializable
data class StartRecordingPayload(
    val sessionIdHint: String? = null
)

@Serializable
data class StopRecordingPayload(
    val mode: StopMode = StopMode.STOP_SERVICE
)

@Serializable
data class PingPayload(
    val clientTimeMs: Long
)

@Serializable
data class PingResponsePayload(
    val clientTimeMs: Long,
    val serverTimeMs: Long,
    val recorderState: RecorderState,
    val uptimeMs: Long
)

@Serializable
enum class SyncTransportMode {
    L2CAP_COC,
    GATT_CHUNKED
}

@Serializable
data class SyncDataRequestPayload(
    val cursorSequence: Long = 0L,
    val maxBatch: Int = 20,
    val preferL2cap: Boolean = true
)

@Serializable
data class SyncSegmentDescriptor(
    val segmentId: String,
    val sessionId: String,
    val sequenceNumber: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val durationMs: Long,
    val wavSizeBytes: Long,
    val sha256Checksum: String
)

@Serializable
data class SyncManifestSummaryPayload(
    val pendingCount: Int,
    val highestSequence: Long,
    val totalPendingBytes: Long,
    val l2capPsm: Int? = null,
    val transportMode: SyncTransportMode = SyncTransportMode.GATT_CHUNKED,
    val segments: List<SyncSegmentDescriptor> = emptyList(),
    val message: String
)

@Serializable
data class SyncAckPayload(
    val segmentId: String,
    val sequenceNumber: Long,
    val verifiedSha256: String
)

@Serializable
data class FetchSegmentChunkRequestPayload(
    val segmentId: String,
    val byteOffset: Long,
    val maxBytes: Int = 2_048
)

@Serializable
data class FetchSegmentChunkResponsePayload(
    val segmentId: String,
    val byteOffset: Long,
    val totalBytes: Long,
    val eof: Boolean,
    val dataBase64: String
)

@Serializable
data class AuthChallengePayload(
    val serverNonceBase64: String,
    val serverTimeMs: Long,
    val deviceId: String,
    val pairedDeviceId: String?
)

@Serializable
data class AuthVerifyPayload(
    val clientDeviceId: String,
    val clientNonceBase64: String,
    val challengeResponseBase64: String,
    val clientTimeMs: Long
)

@Serializable
data class PairingExchangeRequestPayload(
    val clientDeviceId: String,
    val clientDeviceName: String,
    val clientPublicKeyBase64: String
)

@Serializable
data class PairingExchangeResponsePayload(
    val serverDeviceId: String,
    val serverDeviceName: String,
    val serverPublicKeyBase64: String,
    val sasCode: String,
    val accepted: Boolean
)

@Serializable
data class PairingQrPayload(
    val version: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val publicKeyBase64: String
)

@Serializable
data class AcknowledgementPayload(
    val commandId: String,
    val commandType: CommandType,
    val success: Boolean,
    val errorCode: ProtocolErrorCode = ProtocolErrorCode.NONE,
    val message: String = "",
    val serverTimeMs: Long,
    val dataJson: String? = null
)
