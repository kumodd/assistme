package com.personalai.recorder.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RecorderDeviceEntity::class,
        RecordingSessionEntity::class,
        AudioSegmentEntity::class,
        TranscriptSegmentEntity::class,
        SyncQueueEntity::class,
        ProcessedCommandEntity::class,
        DiagnosticEventEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(RecorderTypeConverters::class)
abstract class RecorderDatabase : RoomDatabase() {
    abstract fun recorderDeviceDao(): RecorderDeviceDao
    abstract fun sessionDao(): SessionDao
    abstract fun audioSegmentDao(): AudioSegmentDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun diagnosticDao(): DiagnosticDao
    abstract fun processedCommandDao(): ProcessedCommandDao
}
