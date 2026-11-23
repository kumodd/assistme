package com.personalai.recorder.data.db

import androidx.room.TypeConverter
import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.RecorderState

class RecorderTypeConverters {
    @TypeConverter
    fun fromSessionStatus(value: SessionStatus): String = value.name

    @TypeConverter
    fun toSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)

    @TypeConverter
    fun fromUploadStatus(value: UploadStatus): String = value.name

    @TypeConverter
    fun toUploadStatus(value: String): UploadStatus = UploadStatus.valueOf(value)

    @TypeConverter
    fun fromTranscriptStatus(value: TranscriptProcessingStatus): String = value.name

    @TypeConverter
    fun toTranscriptStatus(value: String): TranscriptProcessingStatus =
        TranscriptProcessingStatus.valueOf(value)

    @TypeConverter
    fun fromSyncItemStatus(value: SyncItemStatus): String = value.name

    @TypeConverter
    fun toSyncItemStatus(value: String): SyncItemStatus = SyncItemStatus.valueOf(value)

    @TypeConverter
    fun fromDiagnosticSeverity(value: DiagnosticSeverity): String = value.name

    @TypeConverter
    fun toDiagnosticSeverity(value: String): DiagnosticSeverity = DiagnosticSeverity.valueOf(value)

    @TypeConverter
    fun fromRecorderState(value: RecorderState): String = value.name

    @TypeConverter
    fun toRecorderState(value: String): RecorderState = RecorderState.valueOf(value)

    @TypeConverter
    fun fromBleState(value: BleConnectionState): String = value.name

    @TypeConverter
    fun toBleState(value: String): BleConnectionState = BleConnectionState.valueOf(value)
}
