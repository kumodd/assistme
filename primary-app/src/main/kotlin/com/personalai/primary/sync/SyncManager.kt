package com.personalai.primary.sync

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.personalai.primary.asr.TranscriptionCoordinator
import com.personalai.primary.ble.BleClientController
import com.personalai.primary.data.db.AsrProcessingStatus
import com.personalai.primary.data.db.PairedRecorderDao
import com.personalai.primary.data.db.SyncedContentDao
import com.personalai.primary.data.db.SyncedSegmentEntity
import com.personalai.primary.data.db.SyncedSessionEntity
import com.personalai.primary.data.prefs.PrimarySettingsRepository
import com.personalai.primary.security.PrimaryEncryptedAudioStorage
import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.L2capSyncFraming
import com.personalai.protocol.SyncSegmentDescriptor
import com.personalai.protocol.SyncTransportMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class SyncProgressSnapshot(
    val isSyncing: Boolean = false,
    val transportLabel: String? = null,
    val completedInRun: Int = 0,
    val totalInBatch: Int = 0,
    val activeSegmentSeq: Long? = null,
    val activeBytesReceived: Long = 0L,
    val activeBytesTotal: Long = 0L,
    val lastSummaryMessage: String? = null,
    val lastError: String? = null
)

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleClient: BleClientController,
    private val pairedDao: PairedRecorderDao,
    private val syncedContentDao: SyncedContentDao,
    private val audioStorage: PrimaryEncryptedAudioStorage,
    private val settingsRepo: PrimarySettingsRepository,
    private val transcriptionCoordinator: TranscriptionCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    private val _progress = MutableStateFlow(SyncProgressSnapshot())
    val progress: StateFlow<SyncProgressSnapshot> = _progress.asStateFlow()

    @Volatile
    private var lastByteProgressEmitMs: Long = 0L

    init {
        // 1) Auto-sync immediately upon becoming READY (reconnect / initial auth).
        scope.launch {
            var prevBle = BleConnectionState.DISCONNECTED
            bleClient.bleState.collect { current ->
                if (current == BleConnectionState.READY && prevBle != BleConnectionState.READY &&
                    prevBle != BleConnectionState.SYNCHRONIZING
                ) {
                    val settings = settingsRepo.getSettings()
                    if (settings.autoSyncOnReconnect) {
                        delay(700L)
                        if (bleClient.bleState.value == BleConnectionState.READY) {
                            triggerSyncNow()
                        }
                    }
                }
                prevBle = current
            }
        }

        // 2) Live push-triggered auto-sync: whenever Secondary seals new segments while ALREADY connected,
        // pull them automatically without requiring disconnect/reconnect or manual Sync taps.
        scope.launch {
            bleClient.remoteStatus
                .map { st ->
                    if (st == null) null else Triple(st.pendingSyncSegments, st.localOnlyPauseSync, st.currentSessionSegments)
                }
                .distinctUntilChanged()
                .collect { state ->
                    val (pendingCount, localOnlyPause, _) = state ?: return@collect
                    if (pendingCount > 0 && !localOnlyPause && bleClient.bleState.value == BleConnectionState.READY) {
                        val settings = settingsRepo.getSettings()
                        if (settings.autoSyncOnReconnect && !syncMutex.isLocked) {
                            delay(1_200L)
                            if (bleClient.bleState.value == BleConnectionState.READY && !syncMutex.isLocked) {
                                triggerSyncNow()
                            }
                        }
                    }
                }
        }
    }

    fun triggerSyncNow() {
        scope.launch {
            syncPendingSegments()
        }
    }

    suspend fun syncPendingSegments() = syncMutex.withLock {
        if (bleClient.bleState.value != BleConnectionState.READY) {
            _progress.value = _progress.value.copy(lastError = "Secondary recorder is not connected & authenticated.")
            return@withLock
        }

        val paired = pairedDao.getPairedDevice()
        if (paired == null) {
            _progress.value = _progress.value.copy(lastError = "No paired Secondary recorder found.")
            return@withLock
        }

        _progress.value = SyncProgressSnapshot(isSyncing = true, lastSummaryMessage = "Querying recorder sync manifest...")
        bleClient.updateSyncBleState(BleConnectionState.SYNCHRONIZING)

        var totalSyncedThisRun = 0
        var keepPolling = true

        try {
            while (keepPolling && (bleClient.bleState.value == BleConnectionState.SYNCHRONIZING ||
                    bleClient.bleState.value == BleConnectionState.READY)
            ) {
                // Always query cursorSequence = 0L: Secondary filters by uploadStatus NOT IN ('ACKED', 'QUARANTINED')
                // and Primary deduplicates by UUID segmentId, preventing stale cursor skips after Secondary resets.
                val manifestRes = bleClient.requestSyncManifest(
                    cursorSequence = 0L,
                    maxBatch = 10,
                    preferL2cap = true
                )
                if (manifestRes.isFailure) {
                    val err = manifestRes.exceptionOrNull()?.message ?: "Manifest request failed"
                    _progress.value = _progress.value.copy(isSyncing = false, lastError = err)
                    bleClient.restoreReadyIfAuthenticated()
                    return@withLock
                }

                val manifest = manifestRes.getOrThrow()
                if (manifest.segments.isEmpty()) {
                    keepPolling = false
                    break
                }

                _progress.value = _progress.value.copy(
                    totalInBatch = manifest.segments.size,
                    transportLabel = manifest.transportMode.name
                )

                val l2capDoneIds = mutableSetOf<String>()
                val psm = manifest.l2capPsm
                val encKey = bleClient.getSessionEncryptionKey()

                if (manifest.transportMode == SyncTransportMode.L2CAP_COC && psm != null && encKey != null) {
                    val sockRes = bleClient.openL2capSocket(psm)
                        .onFailure { android.util.Log.w("SyncManager", "L2CAP CoC open failed (psm=$psm): ${it.message}; falling back to GATT") }
                    if (sockRes.isSuccess) {
                        android.util.Log.i("SyncManager", "L2CAP CoC connected on PSM=$psm for ${manifest.segments.size} segments")
                        sockRes.getOrThrow().use { socket ->
                            val out = socket.outputStream
                            val input = socket.inputStream
                            for (desc in manifest.segments) {
                                if (alreadySyncedLocally(desc)) {
                                    ackAndAdvance(desc)
                                    l2capDoneIds.add(desc.segmentId)
                                    totalSyncedThisRun++
                                    continue
                                }
                                val ok = runCatching {
                                    downloadViaL2cap(desc, out, input, encKey)
                                }.onFailure {
                                    android.util.Log.w("SyncManager", "L2CAP download failed for seg #${desc.sequenceNumber}: ${it.message}")
                                }.getOrDefault(false)
                                if (ok && finalizeVerifiedSegment(paired.deviceId, desc)) {
                                    l2capDoneIds.add(desc.segmentId)
                                    totalSyncedThisRun++
                                    _progress.value = _progress.value.copy(completedInRun = totalSyncedThisRun)
                                } else {
                                    break
                                }
                            }
                            runCatching { L2capSyncFraming.writeEndOfSession(out) }
                        }
                    }
                }

                // Fallback to GATT chunked transfer for any remaining segments in this batch.
                val remaining = manifest.segments.filterNot { it.segmentId in l2capDoneIds }
                if (remaining.isNotEmpty()) {
                    _progress.value = _progress.value.copy(transportLabel = "GATT_CHUNKED")
                    for (desc in remaining) {
                        if (alreadySyncedLocally(desc)) {
                            ackAndAdvance(desc)
                            totalSyncedThisRun++
                            continue
                        }
                        val ok = downloadViaGattChunks(desc)
                        if (ok && finalizeVerifiedSegment(paired.deviceId, desc)) {
                            totalSyncedThisRun++
                            _progress.value = _progress.value.copy(completedInRun = totalSyncedThisRun)
                        } else {
                            keepPolling = false
                            break
                        }
                    }
                }

                if (manifest.segments.size < 10) {
                    keepPolling = false
                }
            }

            val now = System.currentTimeMillis()
            bleClient.markLastSyncCompleted(now)
            bleClient.restoreReadyIfAuthenticated()
            bleClient.refreshStatus()
            bleClient.refreshStorage()

            val msg = if (totalSyncedThisRun > 0) {
                "Synced & verified $totalSyncedThisRun audio segment(s)."
            } else {
                "Up to date — no pending segments on Secondary recorder."
            }
            _progress.value = _progress.value.copy(
                isSyncing = false,
                activeSegmentSeq = null,
                lastSummaryMessage = msg,
                lastError = null
            )

            if (totalSyncedThisRun > 0 && settingsRepo.getSettings().autoTranscribeSynced) {
                transcriptionCoordinator.enqueuePendingProcessing()
                PrimarySyncService.enqueueOfflineProcessingFallback(context)
            }
        } catch (t: Throwable) {
            _progress.value = _progress.value.copy(
                isSyncing = false,
                lastError = t.message ?: "Sync interrupted"
            )
            bleClient.restoreReadyIfAuthenticated()
        }
    }

    private fun emitByteProgressThrottled(bytesReceived: Long, totalBytes: Long) {
        val now = SystemClock.elapsedRealtime()
        if (bytesReceived >= totalBytes || now - lastByteProgressEmitMs >= 200L) {
            lastByteProgressEmitMs = now
            _progress.value = _progress.value.copy(
                activeBytesReceived = bytesReceived,
                activeBytesTotal = totalBytes
            )
        }
    }

    private suspend fun alreadySyncedLocally(desc: SyncSegmentDescriptor): Boolean {
        return syncedContentDao.getSegment(desc.segmentId) != null
    }

    private fun prepareResumablePartFile(desc: SyncSegmentDescriptor): Pair<File, Long> {
        val partFile = audioStorage.getPartFile(desc.segmentId)
        val existingLen = if (partFile.exists()) partFile.length() else 0L
        return if (existingLen > desc.wavSizeBytes) {
            partFile.delete()
            partFile to 0L
        } else {
            partFile to existingLen
        }
    }

    private fun downloadViaL2cap(
        desc: SyncSegmentDescriptor,
        out: java.io.OutputStream,
        input: java.io.InputStream,
        sessionEncKey: ByteArray
    ): Boolean {
        val (partFile, resumeOffset) = prepareResumablePartFile(desc)
        lastByteProgressEmitMs = 0L
        _progress.value = _progress.value.copy(
            activeSegmentSeq = desc.sequenceNumber,
            activeBytesReceived = resumeOffset,
            activeBytesTotal = desc.wavSizeBytes
        )
        if (resumeOffset == desc.wavSizeBytes && desc.wavSizeBytes > 0L) {
            return true
        }

        L2capSyncFraming.writePullRequest(out, desc.segmentId, resumeOffset)
        FileOutputStream(partFile, resumeOffset > 0L).use { fos ->
            L2capSyncFraming.receiveEncryptedSegment(
                input = input,
                segmentId = desc.segmentId,
                resumeOffsetBytes = resumeOffset,
                sessionEncryptionKey = sessionEncKey
            ) { chunk, newOffset, totalBytes ->
                fos.write(chunk)
                emitByteProgressThrottled(newOffset, totalBytes)
            }
            fos.flush()
        }
        return partFile.length() == desc.wavSizeBytes
    }

    private suspend fun downloadViaGattChunks(desc: SyncSegmentDescriptor): Boolean {
        val (partFile, startOffset) = prepareResumablePartFile(desc)
        var offset = startOffset
        lastByteProgressEmitMs = 0L
        _progress.value = _progress.value.copy(
            activeSegmentSeq = desc.sequenceNumber,
            activeBytesReceived = offset,
            activeBytesTotal = desc.wavSizeBytes
        )

        FileOutputStream(partFile, offset > 0L).use { fos ->
            while (offset < desc.wavSizeBytes) {
                val res = bleClient.fetchSegmentChunkOverGatt(
                    segmentId = desc.segmentId,
                    byteOffset = offset,
                    maxBytes = 2_048
                )
                if (res.isFailure) {
                    _progress.value = _progress.value.copy(lastError = res.exceptionOrNull()?.message)
                    return false
                }
                val chunkPayload = res.getOrThrow()
                val rawChunk = Base64.decode(chunkPayload.dataBase64, Base64.NO_WRAP)
                if (rawChunk.isNotEmpty()) {
                    fos.write(rawChunk)
                    offset += rawChunk.size
                    emitByteProgressThrottled(offset, chunkPayload.totalBytes)
                }
                if (chunkPayload.eof || rawChunk.isEmpty()) break
            }
            fos.flush()
        }
        return partFile.length() == desc.wavSizeBytes
    }

    private suspend fun finalizeVerifiedSegment(
        deviceId: String,
        desc: SyncSegmentDescriptor
    ): Boolean {
        val partFile = audioStorage.getPartFile(desc.segmentId)
        if (!partFile.exists()) return false

        val actualSha = audioStorage.sha256FileHex(partFile)
        if (!actualSha.equals(desc.sha256Checksum, ignoreCase = true)) {
            audioStorage.deletePartFile(desc.segmentId)
            _progress.value = _progress.value.copy(
                lastError = "SHA-256 mismatch on segment #${desc.sequenceNumber}; discarded partial file"
            )
            return false
        }

        val encRelPath = audioStorage.encryptFromWavFile(desc.sessionId, desc.segmentId, partFile)
        audioStorage.deletePartFile(desc.segmentId)

        val now = System.currentTimeMillis()
        val existingSession = syncedContentDao.getSession(desc.sessionId)
        if (existingSession == null) {
            syncedContentDao.upsertSession(
                SyncedSessionEntity(
                    sessionId = desc.sessionId,
                    deviceId = deviceId,
                    firstSegmentAtMs = desc.startTimestampMs,
                    lastSegmentAtMs = desc.endTimestampMs,
                    segmentCount = 1
                )
            )
        } else {
            syncedContentDao.upsertSession(
                existingSession.copy(
                    firstSegmentAtMs = minOf(existingSession.firstSegmentAtMs, desc.startTimestampMs),
                    lastSegmentAtMs = maxOf(existingSession.lastSegmentAtMs, desc.endTimestampMs),
                    segmentCount = existingSession.segmentCount + 1
                )
            )
        }

        val insertedRowId = syncedContentDao.insertSegment(
            SyncedSegmentEntity(
                segmentId = desc.segmentId,
                sessionId = desc.sessionId,
                sequenceNumber = desc.sequenceNumber,
                startTimestampMs = desc.startTimestampMs,
                endTimestampMs = desc.endTimestampMs,
                durationMs = desc.durationMs,
                localEncryptedAudioPath = encRelPath,
                wavSizeBytes = desc.wavSizeBytes,
                checksum = actualSha,
                syncedAtMs = now,
                asrStatus = AsrProcessingStatus.PENDING
            )
        )
        // Safety invariant: NEVER acknowledge a segment to Secondary unless confirmed persisted in Room.
        if (insertedRowId == -1L && syncedContentDao.getSegment(desc.segmentId) == null) {
            android.util.Log.e("SyncManager", "Refusing SYNC_ACK: segment ${desc.segmentId} (#${desc.sequenceNumber}) was not persisted")
            _progress.value = _progress.value.copy(lastError = "Local DB insert skipped segment #${desc.sequenceNumber}; audio kept safely on Secondary.")
            return false
        }

        ackAndAdvance(desc)
        return true
    }

    private suspend fun ackAndAdvance(desc: SyncSegmentDescriptor) {
        val ackRes = bleClient.sendSyncAck(
            segmentId = desc.segmentId,
            sequenceNumber = desc.sequenceNumber,
            verifiedSha256 = desc.sha256Checksum
        )
        if (ackRes.isSuccess) {
            settingsRepo.updateLastSyncedSequence(desc.sequenceNumber)
        }
    }
}
