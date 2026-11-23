package com.personalai.recorder.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.RecorderState

enum class SessionStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    INTERRUPTED,
    FAILED
}

enum class UploadStatus {
    LOCAL_ONLY,
    QUEUED,
    IN_PROGRESS,
    TRANSFERRED_UNVERIFIED,
    ACKED,
    QUARANTINED
}

enum class TranscriptProcessingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    SKIPPED_THERMAL
}

enum class SyncItemStatus {
    PENDING,
    SENDING,
    AWAITING_ACK,
    ACKED,
    FAILED
}

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR
}

@Entity(tableName = "recorder_devices")
data class RecorderDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val firmwareVersion: String,
    val pairedDeviceId: String? = null,
    val pairedDeviceBondAddress: String? = null,
    val createdAt: Long,
    val lastConnectedAt: Long? = null
)

@Entity(
    tableName = "recording_sessions",
    indices = [Index(value = ["status"]), Index(value = ["startTime"])]
)
data class RecordingSessionEntity(
    @PrimaryKey val sessionId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val status: SessionStatus,
    val interruptionCount: Int = 0,
    val totalSegments: Int = 0,
    val terminationReason: String? = null
)

@Entity(
    tableName = "audio_segments",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sequenceNumber"], unique = true),
        Index(value = ["uploadStatus"]),
        Index(value = ["createdAt"])
    ]
)
data class AudioSegmentEntity(
    @PrimaryKey val segmentId: String,
    val sessionId: String,
    val sequenceNumber: Long,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val duration: Long,
    val filePath: String,
    val fileSize: Long,
    val encryptedSize: Long,
    val checksum: String,
    val uploadStatus: UploadStatus,
    val createdAt: Long
)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = AudioSegmentEntity::class,
            parentColumns = ["segmentId"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["segmentId"], unique = true)]
)
data class TranscriptSegmentEntity(
    @PrimaryKey val transcriptId: String,
    val segmentId: String,
    val text: String,
    val language: String,
    val confidence: Float,
    val processingStatus: TranscriptProcessingStatus
)

@Entity(
    tableName = "sync_queue",
    foreignKeys = [
        ForeignKey(
            entity = AudioSegmentEntity::class,
            parentColumns = ["segmentId"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["segmentId"], unique = true),
        Index(value = ["syncStatus"])
    ]
)
data class SyncQueueEntity(
    @PrimaryKey val itemId: String,
    val segmentId: String,
    val syncStatus: SyncItemStatus,
    val bytesTransferred: Long = 0L,
    val retryCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null
)

@Entity(
    tableName = "processed_commands",
    indices = [Index(value = ["expiresAt"])]
)
data class ProcessedCommandEntity(
    @PrimaryKey val commandId: String,
    val sequenceNumber: Long,
    val receivedAt: Long,
    val expiresAt: Long,
    val resultCode: String,
    val responsePayloadBase64: String
)

@Entity(
    tableName = "diagnostic_events",
    indices = [Index(value = ["timestamp"])]
)
data class DiagnosticEventEntity(
    @PrimaryKey val eventId: String,
    val timestamp: Long,
    val category: String,
    val severity: DiagnosticSeverity,
    val recorderState: RecorderState,
    val bleState: BleConnectionState,
    val message: String,
    val metadataJson: String? = null
)
