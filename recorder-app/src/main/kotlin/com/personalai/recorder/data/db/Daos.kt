package com.personalai.recorder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecorderDeviceDao {
    @Query("SELECT * FROM recorder_devices LIMIT 1")
    fun observeDevice(): Flow<RecorderDeviceEntity?>

    @Query("SELECT * FROM recorder_devices LIMIT 1")
    suspend fun getDevice(): RecorderDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: RecorderDeviceEntity)

    @Query(
        "UPDATE recorder_devices SET pairedDeviceId = :pairedDeviceId, pairedDeviceBondAddress = :bondAddress, lastConnectedAt = :lastConnectedAt WHERE deviceId = :deviceId"
    )
    suspend fun updatePairedPeer(
        deviceId: String,
        pairedDeviceId: String?,
        bondAddress: String?,
        lastConnectedAt: Long?
    )

    @Query("UPDATE recorder_devices SET lastConnectedAt = :lastConnectedAt WHERE deviceId = :deviceId")
    suspend fun updateLastConnected(deviceId: String, lastConnectedAt: Long)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: RecordingSessionEntity)

    @Query("SELECT * FROM recording_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): RecordingSessionEntity?

    @Query("SELECT * FROM recording_sessions ORDER BY startTime DESC LIMIT 1")
    fun observeLatestSession(): Flow<RecordingSessionEntity?>

    @Query("SELECT * FROM recording_sessions WHERE status = 'ACTIVE' ORDER BY startTime DESC")
    suspend fun getActiveSessions(): List<RecordingSessionEntity>

    @Query(
        "UPDATE recording_sessions SET status = :status, endTime = :endTime, terminationReason = :reason WHERE sessionId = :sessionId"
    )
    suspend fun closeSession(
        sessionId: String,
        status: SessionStatus,
        endTime: Long,
        reason: String?
    )

    @Query("UPDATE recording_sessions SET interruptionCount = interruptionCount + 1 WHERE sessionId = :sessionId")
    suspend fun incrementInterruptionCount(sessionId: String)

    @Query("UPDATE recording_sessions SET totalSegments = totalSegments + 1 WHERE sessionId = :sessionId")
    suspend fun incrementTotalSegments(sessionId: String)

    @Query("DELETE FROM recording_sessions")
    suspend fun deleteAll()
}

@Dao
interface AudioSegmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(segment: AudioSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSyncItem(item: SyncQueueEntity)

    @Query("UPDATE recording_sessions SET totalSegments = totalSegments + 1 WHERE sessionId = :sessionId")
    suspend fun incrementSessionSegmentCount(sessionId: String)

    @Transaction
    suspend fun insertSegmentAndEnqueue(segment: AudioSegmentEntity, queueItem: SyncQueueEntity) {
        insertSegment(segment)
        insertSyncItem(queueItem)
        incrementSessionSegmentCount(segment.sessionId)
    }

    @Query("SELECT * FROM audio_segments ORDER BY sequenceNumber DESC LIMIT :limit")
    fun observeRecentSegments(limit: Int = 50): Flow<List<AudioSegmentEntity>>

    @Query("SELECT COUNT(*) FROM audio_segments")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(encryptedSize), 0) FROM audio_segments")
    fun observeTotalStoredBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(encryptedSize), 0) FROM audio_segments")
    suspend fun getTotalStoredBytes(): Long

    @Query("SELECT COUNT(*) FROM audio_segments")
    suspend fun getTotalCount(): Int

    @Query("SELECT COALESCE(MAX(sequenceNumber), 0) FROM audio_segments")
    suspend fun getMaxSequenceNumber(): Long

    @Query("SELECT * FROM audio_segments WHERE segmentId = :segmentId")
    suspend fun getSegment(segmentId: String): AudioSegmentEntity?

    @Query("SELECT * FROM audio_segments ORDER BY sequenceNumber ASC")
    suspend fun getAllSegmentsOrdered(): List<AudioSegmentEntity>

    @Query(
        "SELECT * FROM audio_segments WHERE uploadStatus NOT IN ('ACKED', 'QUARANTINED') AND sequenceNumber > :cursorSequence ORDER BY sequenceNumber ASC LIMIT :limit"
    )
    suspend fun getPendingSegmentsAfter(cursorSequence: Long, limit: Int): List<AudioSegmentEntity>

    @Query("UPDATE audio_segments SET uploadStatus = :status WHERE segmentId = :segmentId")
    suspend fun updateUploadStatus(segmentId: String, status: UploadStatus)

    @Query("DELETE FROM audio_segments WHERE segmentId = :segmentId")
    suspend fun deleteSegment(segmentId: String)

    @Query("DELETE FROM audio_segments")
    suspend fun deleteAll()
}

@Dao
interface SyncQueueDao {
    @Query("SELECT COUNT(*) FROM sync_queue WHERE syncStatus != 'ACKED'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE syncStatus != 'ACKED'")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM sync_queue WHERE syncStatus != 'ACKED' ORDER BY lastAttemptAt ASC")
    suspend fun getPendingItems(): List<SyncQueueEntity>

    @Query("UPDATE sync_queue SET syncStatus = :status, lastAttemptAt = :attemptAt, lastError = :error WHERE itemId = :itemId")
    suspend fun updateStatus(itemId: String, status: SyncItemStatus, attemptAt: Long, error: String?)

    @Query("UPDATE sync_queue SET syncStatus = 'ACKED', lastAttemptAt = :nowMs, lastError = NULL WHERE segmentId = :segmentId")
    suspend fun markAckedBySegmentId(segmentId: String, nowMs: Long)

    @Query(
        "UPDATE sync_queue SET syncStatus = 'FAILED', retryCount = retryCount + 1, lastAttemptAt = :nowMs, lastError = :error WHERE segmentId = :segmentId"
    )
    suspend fun markFailedBySegmentId(segmentId: String, nowMs: Long, error: String)

    @Query(
        "UPDATE sync_queue SET syncStatus = :status, bytesTransferred = :bytesTransferred, lastAttemptAt = :nowMs WHERE segmentId = :segmentId"
    )
    suspend fun updateProgressBySegmentId(
        segmentId: String,
        status: SyncItemStatus,
        bytesTransferred: Long,
        nowMs: Long
    )

    @Query("DELETE FROM sync_queue")
    suspend fun deleteAll()
}

@Dao
interface DiagnosticDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentEvents(limit: Int = 100): Flow<List<DiagnosticEventEntity>>

    @Query("DELETE FROM diagnostic_events WHERE timestamp < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long)

    @Query("DELETE FROM diagnostic_events")
    suspend fun deleteAll()
}

@Dao
interface ProcessedCommandDao {
    @Query("SELECT * FROM processed_commands WHERE commandId = :commandId LIMIT 1")
    suspend fun findById(commandId: String): ProcessedCommandEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProcessedCommandEntity)

    @Query("DELETE FROM processed_commands WHERE expiresAt < :nowMs")
    suspend fun pruneExpired(nowMs: Long)

    @Query("DELETE FROM processed_commands")
    suspend fun deleteAll()
}
