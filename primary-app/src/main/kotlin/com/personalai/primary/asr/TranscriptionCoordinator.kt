package com.personalai.primary.asr

import com.personalai.primary.data.db.AsrProcessingStatus
import com.personalai.primary.data.db.SyncedContentDao
import com.personalai.primary.data.db.SyncedSegmentEntity
import com.personalai.primary.data.db.TranscriptDao
import com.personalai.primary.data.db.TranscriptEntity
import com.personalai.primary.data.prefs.PrimarySettingsRepository
import com.personalai.primary.memory.MemoryCoordinator
import com.personalai.primary.security.PrimaryEncryptedAudioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class AsrProgressSnapshot(
    val isTranscribing: Boolean = false,
    val activeSegmentSequence: Long? = null,
    val lastProviderUsed: String? = null,
    val lastError: String? = null
)

@Singleton
class TranscriptionCoordinator @Inject constructor(
    private val syncedContentDao: SyncedContentDao,
    private val transcriptDao: TranscriptDao,
    private val audioStorage: PrimaryEncryptedAudioStorage,
    private val settingsRepo: PrimarySettingsRepository,
    private val localWhisperProvider: LocalWhisperAsrProvider,
    private val androidOnDeviceProvider: AndroidOnDeviceAsrProvider,
    private val memoryCoordinator: MemoryCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processingMutex = Mutex()

    private val _progress = MutableStateFlow(AsrProgressSnapshot())
    val progress: StateFlow<AsrProgressSnapshot> = _progress.asStateFlow()

    init {
        scope.launch {
            syncedContentDao.resetInterruptedProcessingToPending()
        }
    }

    fun enqueuePendingProcessing() {
        scope.launch {
            processPendingQueue()
        }
    }

    fun retrySegment(segmentId: String) {
        scope.launch {
            syncedContentDao.updateAsrStatus(segmentId, AsrProcessingStatus.PENDING, null)
            processPendingQueue()
        }
    }

    fun retryAllFailed() {
        scope.launch {
            syncedContentDao.resetFailedAsrToPending()
            processPendingQueue()
        }
    }

    suspend fun processPendingQueue() = processingMutex.withLock {
        val pending = syncedContentDao.getPendingAsrSegments()
        if (pending.isEmpty()) return@withLock

        val settings = settingsRepo.getSettings()
        for (segment in pending) {
            transcribeSingleSegment(
                segment = segment,
                providerType = settings.asrProviderType,
                languageMode = settings.asrLanguageMode,
                localBaseUrl = settings.localWhisperBaseUrl
            )
        }
        _progress.value = _progress.value.copy(
            isTranscribing = false,
            activeSegmentSequence = null
        )
    }

    private suspend fun transcribeSingleSegment(
        segment: SyncedSegmentEntity,
        providerType: AsrProviderType,
        languageMode: AsrLanguageMode,
        localBaseUrl: String
    ) {
        _progress.value = _progress.value.copy(
            isTranscribing = true,
            activeSegmentSequence = segment.sequenceNumber,
            lastError = null
        )
        syncedContentDao.updateAsrStatus(segment.segmentId, AsrProcessingStatus.PROCESSING, null)

        if (segment.audioDeleted) {
            val msg = "Audio chunk was already pruned to save space (transcript preserved)."
            _progress.value = _progress.value.copy(isTranscribing = false, activeSegmentSequence = null, lastError = msg)
            return
        }

        val wavBytes = runCatching {
            audioStorage.decryptWavBytes(
                relativePath = segment.localEncryptedAudioPath,
                sessionId = segment.sessionId,
                segmentId = segment.segmentId
            )
        }.getOrElse { err ->
            // Never delete original encrypted audio on read/ASR error.
            val msg = "Audio decrypt error: ${err.message}"
            syncedContentDao.updateAsrStatus(segment.segmentId, AsrProcessingStatus.FAILED, msg)
            _progress.value = _progress.value.copy(lastError = msg)
            return
        }

        val request = AsrRequest(
            segmentId = segment.segmentId,
            wavBytes = wavBytes,
            languageMode = languageMode,
            localBaseUrl = localBaseUrl
        )

        val result = when (providerType) {
            AsrProviderType.ANDROID_ON_DEVICE -> androidOnDeviceProvider.transcribe(request)
            AsrProviderType.LOCAL_WHISPER_SERVER -> localWhisperProvider.transcribe(request)
        }

        result.onSuccess { asr ->
            val entity = TranscriptEntity(
                transcriptId = UUID.randomUUID().toString(),
                segmentId = segment.segmentId,
                sessionId = segment.sessionId,
                text = asr.text.ifBlank { "[Inaudible / No Speech Text]" },
                language = asr.detectedLanguage,
                startTimeMs = segment.startTimestampMs,
                endTimeMs = segment.endTimestampMs,
                confidence = asr.confidence,
                speakerLabel = asr.speakerLabel,
                asrProvider = asr.providerName,
                createdAtMs = System.currentTimeMillis()
            )
            transcriptDao.upsert(entity)
            syncedContentDao.updateAsrStatus(segment.segmentId, AsrProcessingStatus.COMPLETED, null)
            _progress.value = _progress.value.copy(
                lastProviderUsed = asr.providerName,
                lastError = null
            )
            if (settingsRepo.getSettings().autoExtractMemory) {
                memoryCoordinator.enqueuePendingExtraction()
            }
        }.onFailure { err ->
            // PRD Section 11: ASR failure must NEVER delete the synced encrypted audio.
            val msg = err.message ?: "Transcription failed"
            syncedContentDao.updateAsrStatus(segment.segmentId, AsrProcessingStatus.FAILED, msg)
            _progress.value = _progress.value.copy(lastError = msg)
        }
    }
}
