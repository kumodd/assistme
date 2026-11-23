package com.personalai.recorder.data.repo

import android.os.Build
import com.personalai.recorder.BuildConfig
import com.personalai.recorder.audio.CompletedSpeechSegment
import com.personalai.recorder.data.db.AudioSegmentDao
import com.personalai.recorder.data.db.AudioSegmentEntity
import com.personalai.recorder.data.db.DiagnosticDao
import com.personalai.recorder.data.db.DiagnosticEventEntity
import com.personalai.recorder.data.db.DiagnosticSeverity
import com.personalai.recorder.data.db.RecorderDeviceDao
import com.personalai.recorder.data.db.RecorderDeviceEntity
import com.personalai.recorder.data.db.RecordingSessionEntity
import com.personalai.recorder.data.db.SessionDao
import com.personalai.recorder.data.db.SessionStatus
import com.personalai.recorder.data.db.SyncItemStatus
import com.personalai.recorder.data.db.SyncQueueDao
import com.personalai.recorder.data.db.SyncQueueEntity
import com.personalai.recorder.data.db.UploadStatus
import com.personalai.recorder.data.prefs.RecorderPreferencesRepository
import com.personalai.recorder.domain.state.RecorderEvent
import com.personalai.recorder.domain.state.RecorderStateMachine
import com.personalai.recorder.security.EncryptedSegmentStorage
import com.personalai.recorder.security.MasterKeyProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecorderRepository @Inject constructor(
    private val deviceDao: RecorderDeviceDao,
    private val sessionDao: SessionDao,
    private val segmentDao: AudioSegmentDao,
    private val syncQueueDao: SyncQueueDao,
    private val diagnosticDao: DiagnosticDao,
    private val prefs: RecorderPreferencesRepository,
    private val segmentStorage: EncryptedSegmentStorage,
    private val masterKeyProvider: MasterKeyProvider,
    private val stateMachine: RecorderStateMachine
) {
    fun observeDevice(): Flow<RecorderDeviceEntity?> = deviceDao.observeDevice()
    fun observeLatestSession(): Flow<RecordingSessionEntity?> = sessionDao.observeLatestSession()
    fun observeRecentSegments(limit: Int = 50): Flow<List<AudioSegmentEntity>> =
        segmentDao.observeRecentSegments(limit)
    fun observeTotalSegmentCount(): Flow<Int> = segmentDao.observeTotalCount()
    fun observeTotalStoredBytes(): Flow<Long> = segmentDao.observeTotalStoredBytes()
    fun observePendingSyncCount(): Flow<Int> = syncQueueDao.observePendingCount()
    fun observeDiagnosticEvents(limit: Int = 100): Flow<List<DiagnosticEventEntity>> =
        diagnosticDao.observeRecentEvents(limit)

    suspend fun ensureDeviceInitialized(): RecorderDeviceEntity {
        masterKeyProvider.getOrCreateSecretKey()
        val existing = deviceDao.getDevice()
        if (existing != null) return existing

        val created = RecorderDeviceEntity(
            deviceId = UUID.randomUUID().toString(),
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            firmwareVersion = BuildConfig.VERSION_NAME,
            createdAt = System.currentTimeMillis()
        )
        deviceDao.upsertDevice(created)
        logEvent(
            category = "SETUP",
            severity = DiagnosticSeverity.INFO,
            message = "Initialized device identity ${created.deviceId} and Android Keystore AES-256-GCM key"
        )
        return created
    }

    suspend fun startSession(sessionId: String): RecordingSessionEntity {
        val now = System.currentTimeMillis()
        val session = RecordingSessionEntity(
            sessionId = sessionId,
            startTime = now,
            status = SessionStatus.ACTIVE
        )
        sessionDao.insertSession(session)
        prefs.markSessionRunning(sessionId, now)
        logEvent("SESSION", DiagnosticSeverity.INFO, "Started recording session $sessionId")
        return session
    }

    suspend fun updateHeartbeat(sessionId: String) {
        prefs.updateSessionHeartbeat(sessionId, System.currentTimeMillis())
    }

    suspend fun closeSession(sessionId: String, status: SessionStatus, reason: String) {
        val now = System.currentTimeMillis()
        sessionDao.closeSession(sessionId, status, now, reason)
        prefs.markSessionCleanShutdown(sessionId)
        logEvent(
            category = "SESSION",
            severity = if (status == SessionStatus.FAILED || status == SessionStatus.INTERRUPTED) {
                DiagnosticSeverity.WARNING
            } else {
                DiagnosticSeverity.INFO
            },
            message = "Closed session $sessionId with status=$status reason=$reason"
        )
    }

    suspend fun recordInterruption(sessionId: String, reason: String) {
        sessionDao.incrementInterruptionCount(sessionId)
        logEvent("AUDIO_INTERRUPTION", DiagnosticSeverity.WARNING, reason)
    }

    suspend fun persistEncryptedSpeechSegment(
        segment: CompletedSpeechSegment,
        sampleRateHz: Int,
        channels: Int
    ): AudioSegmentEntity {
        val maxExistingSeq = segmentDao.getMaxSequenceNumber()
        val seq = prefs.allocateNextSequenceNumber(minFloor = maxExistingSeq)

        val stored = segmentStorage.writeEncryptedWav(
            segmentId = segment.segmentId,
            sessionId = segment.sessionId,
            sequenceNumber = seq,
            pcmSamples = segment.samples,
            sampleRateHz = sampleRateHz,
            channels = channels
        )

        val now = System.currentTimeMillis()
        val entity = AudioSegmentEntity(
            segmentId = segment.segmentId,
            sessionId = segment.sessionId,
            sequenceNumber = seq,
            startTimestamp = segment.startTimestampMs,
            endTimestamp = segment.endTimestampMs,
            duration = segment.durationMs,
            filePath = stored.relativePath,
            fileSize = stored.plaintextSize,
            encryptedSize = stored.encryptedSize,
            checksum = stored.sha256Checksum,
            uploadStatus = UploadStatus.QUEUED,
            createdAt = now
        )
        val queueItem = SyncQueueEntity(
            itemId = UUID.randomUUID().toString(),
            segmentId = segment.segmentId,
            syncStatus = SyncItemStatus.PENDING,
            bytesTransferred = 0L,
            retryCount = 0
        )

        segmentDao.insertSegmentAndEnqueue(entity, queueItem)
        stateMachine.incrementSessionSegments()
        logEvent(
            category = "SEGMENT_SAVED",
            severity = DiagnosticSeverity.INFO,
            message = "Saved encrypted segment #$seq (${segment.durationMs} ms, ${stored.encryptedSize} bytes, reason=${segment.closeReason})"
        )
        return entity
    }

    suspend fun verifySegmentIntegrity(segmentId: String): Boolean {
        val segment = segmentDao.getSegment(segmentId) ?: return false
        return try {
            segmentStorage.decryptAndVerify(segment)
            true
        } catch (t: Throwable) {
            segmentStorage.quarantineFile(segment.filePath)
            segmentDao.updateUploadStatus(segment.segmentId, UploadStatus.QUARANTINED)
            logEvent(
                category = "INTEGRITY",
                severity = DiagnosticSeverity.ERROR,
                message = "Quarantined corrupted segment ${segment.segmentId}: ${t.message}"
            )
            false
        }
    }

    suspend fun reconcileUncleanShutdownIfAny(isServiceCurrentlyRunning: Boolean): Boolean {
        if (isServiceCurrentlyRunning) return false
        val marker = prefs.readActiveSessionMarker()
        val activeSessions = sessionDao.getActiveSessions()
        if ((marker != null && !marker.cleanShutdown) || activeSessions.isNotEmpty()) {
            val now = System.currentTimeMillis()
            for (session in activeSessions) {
                sessionDao.closeSession(
                    sessionId = session.sessionId,
                    status = SessionStatus.INTERRUPTED,
                    endTime = marker?.lastHeartbeatMs?.takeIf { it > 0L } ?: now,
                    reason = "PROCESS_KILLED_OR_REBOOTED"
                )
            }
            prefs.clearActiveSessionMarker()
            stateMachine.dispatch(
                RecorderEvent.ServiceInterruptedDetected(
                    "Previous recording session terminated unexpectedly (OS kill or device reboot). Tap Arm Recorder to resume."
                )
            )
            logEvent(
                category = "RECOVERY",
                severity = DiagnosticSeverity.WARNING,
                message = "Detected unclean shutdown of ${activeSessions.size} active session(s); marked SERVICE_INTERRUPTED"
            )
            return true
        }
        return false
    }

    suspend fun wipeAllRecordingsAndLogs() {
        segmentStorage.wipeAllRecordings()
        syncQueueDao.deleteAll()
        segmentDao.deleteAll()
        sessionDao.deleteAll()
        logEvent(
            category = "PRIVACY",
            severity = DiagnosticSeverity.INFO,
            message = "User wiped all local recordings, sessions, and sync queue entries"
        )
    }

    suspend fun getStorageMetrics(): Triple<Long, Int, Int> {
        val usedBytes = segmentDao.getTotalStoredBytes()
        val pendingCount = syncQueueDao.getPendingCount()
        val totalCount = segmentDao.getTotalCount()
        return Triple(usedBytes, pendingCount, totalCount)
    }

    suspend fun getMaxSequenceNumber(): Long = segmentDao.getMaxSequenceNumber()

    suspend fun setPairedPeer(pairedDeviceId: String?, bondAddress: String?) {
        val dev = ensureDeviceInitialized()
        deviceDao.updatePairedPeer(
            deviceId = dev.deviceId,
            pairedDeviceId = pairedDeviceId,
            bondAddress = bondAddress,
            lastConnectedAt = System.currentTimeMillis()
        )
    }

    suspend fun markPeerConnected() {
        val dev = ensureDeviceInitialized()
        deviceDao.updateLastConnected(dev.deviceId, System.currentTimeMillis())
    }

    suspend fun getPendingSyncBatch(cursorSequence: Long, maxBatch: Int): List<AudioSegmentEntity> {
        return segmentDao.getPendingSegmentsAfter(
            cursorSequence = cursorSequence.coerceAtLeast(0L),
            limit = maxBatch.coerceIn(1, 50)
        )
    }

    suspend fun readDecryptedSegmentWavForSync(segmentId: String): Result<ByteArray> {
        val segment = segmentDao.getSegment(segmentId)
            ?: return Result.failure(NoSuchElementException("Segment not found: $segmentId"))
        return try {
            val wavBytes = segmentStorage.decryptAndVerify(segment)
            segmentDao.updateUploadStatus(segmentId, UploadStatus.IN_PROGRESS)
            syncQueueDao.updateProgressBySegmentId(
                segmentId = segmentId,
                status = SyncItemStatus.SENDING,
                bytesTransferred = 0L,
                nowMs = System.currentTimeMillis()
            )
            Result.success(wavBytes)
        } catch (t: Throwable) {
            segmentStorage.quarantineFile(segment.filePath)
            segmentDao.updateUploadStatus(segmentId, UploadStatus.QUARANTINED)
            syncQueueDao.markFailedBySegmentId(
                segmentId = segmentId,
                nowMs = System.currentTimeMillis(),
                error = "Corrupt/quarantined: ${t.message}"
            )
            logEvent(
                category = "SYNC_QUARANTINE",
                severity = DiagnosticSeverity.ERROR,
                message = "Quarantined corrupt segment #$segmentId during sync: ${t.message}"
            )
            Result.failure(t)
        }
    }

    suspend fun acknowledgeSyncedSegment(segmentId: String, verifiedSha256: String): Result<Boolean> {
        val segment = segmentDao.getSegment(segmentId)
            ?: return Result.failure(NoSuchElementException("Unknown segment $segmentId"))
        if (!segment.checksum.equals(verifiedSha256, ignoreCase = true)) {
            syncQueueDao.markFailedBySegmentId(
                segmentId = segmentId,
                nowMs = System.currentTimeMillis(),
                error = "ACK checksum mismatch"
            )
            return Result.failure(SecurityException("ACK checksum mismatch for $segmentId"))
        }

        val now = System.currentTimeMillis()
        segmentDao.updateUploadStatus(segmentId, UploadStatus.ACKED)
        syncQueueDao.markAckedBySegmentId(segmentId, now)

        val autoDelete = prefs.settingsFlow.first().autoDeleteSyncedSegments
        var deletedLocal = false
        if (autoDelete) {
            deletedLocal = segmentStorage.deleteSegmentFile(segment.filePath)
        }
        logEvent(
            category = "SYNC_ACK",
            severity = DiagnosticSeverity.INFO,
            message = "Verified SYNC_ACK for segment #${segment.sequenceNumber} (deletedLocal=$deletedLocal)"
        )
        return Result.success(deletedLocal)
    }

    suspend fun logEvent(
        category: String,
        severity: DiagnosticSeverity,
        message: String,
        metadataJson: String? = null
    ) {
        val snap = stateMachine.snapshot.value
        diagnosticDao.insertEvent(
            DiagnosticEventEntity(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                category = category,
                severity = severity,
                recorderState = snap.recorderState,
                bleState = snap.bleState,
                message = message,
                metadataJson = metadataJson
            )
        )
    }
}
