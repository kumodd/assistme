package com.personalai.primary.asr

import android.util.Log
import com.personalai.primary.audio.AudioRetentionManager
import com.personalai.primary.data.db.AsrProcessingStatus
import com.personalai.primary.data.db.SyncedContentDao
import com.personalai.primary.data.db.SyncedSegmentEntity
import com.personalai.primary.data.db.TranscriptDao
import com.personalai.primary.data.db.TranscriptEntity
import com.personalai.primary.data.prefs.AppDeviceRole
import com.personalai.primary.data.prefs.PrimarySettingsRepository
import com.personalai.primary.memory.MemoryCoordinator
import com.personalai.primary.security.PrimaryEncryptedAudioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val uoaiAsrProvider: UoaiAsrProvider,
    private val localWhisperProvider: LocalWhisperAsrProvider,
    private val androidOnDeviceProvider: AndroidOnDeviceAsrProvider,
    private val memoryCoordinator: MemoryCoordinator,
    private val audioRetentionManager: AudioRetentionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processingMutex = Mutex()

    private val _progress = MutableStateFlow(AsrProgressSnapshot())
    val progress: StateFlow<AsrProgressSnapshot> = _progress.asStateFlow()

    init {
        scope.launch {
            syncedContentDao.resetInterruptedProcessingToPending()
            // Automatically resume interrupted/pending offline transcriptions after process restart in Primary mode.
            if (settingsRepo.getDeviceRole() == AppDeviceRole.PRIMARY &&
                settingsRepo.getSettings().autoTranscribeSynced
            ) {
                processPendingQueue()
            }
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
                localBaseUrl = settings.localWhisperBaseUrl,
                uoaiProviderId = settings.uoaiAsrProviderId,
                uoaiModel = settings.uoaiAsrModel,
                uoaiScript = settings.uoaiAsrScript
            )
        }
        _progress.value = _progress.value.copy(
            isTranscribing = false,
            activeSegmentSequence = null
        )
        runCatching { audioRetentionManager.enforceRetentionPolicy() }
    }

    private suspend fun transcribeSingleSegment(
        segment: SyncedSegmentEntity,
        providerType: AsrProviderType,
        languageMode: AsrLanguageMode,
        localBaseUrl: String,
        uoaiProviderId: String,
        uoaiModel: String,
        uoaiScript: UoaiAsrScript
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
            localBaseUrl = localBaseUrl,
            uoaiProviderId = uoaiProviderId,
            uoaiModel = uoaiModel,
            uoaiScript = uoaiScript
        )

        // Bounded automatic retry on transient SODA / UOAI busy, thermal throttle, or timeout failures.
        var result: Result<AsrResult> = Result.failure(IllegalStateException("ASR did not execute"))
        val retryDelays = listOf(1_500L, 3_500L)
        for (attempt in 0..retryDelays.size) {
            result = when (providerType) {
                AsrProviderType.UOAI_PROVIDER -> uoaiAsrProvider.transcribe(request)
                AsrProviderType.ANDROID_ON_DEVICE -> androidOnDeviceProvider.transcribe(request)
                AsrProviderType.LOCAL_WHISPER_SERVER -> localWhisperProvider.transcribe(request)
            }
            if (result.isSuccess) break
            val err = result.exceptionOrNull()
            val retryable = err is AndroidOnDeviceAsrProvider.TransientAsrException ||
                err is UoaiAsrProvider.TransientUoaiAsrException
            if (!retryable || attempt >= retryDelays.size) break
            Log.w(TAG, "Transient ASR error on seg #${segment.sequenceNumber} (attempt ${attempt + 1}): ${err?.message}; retrying in ${retryDelays[attempt]}ms")
            delay(retryDelays[attempt])
        }

        result.onSuccess { asr ->
            val cleanedText = asr.text.trim()
            val hasSpeechText = cleanedText.isNotEmpty()
            val finalText = if (hasSpeechText) cleanedText else NO_SPEECH_PLACEHOLDER
            val now = System.currentTimeMillis()

            // CRITICAL: Preserve existing transcriptId on Re-ASR so ON DELETE CASCADE never wipes
            // user-edited or pinned memories linked to this segment!
            val existing = transcriptDao.getBySegmentId(segment.segmentId)
            if (existing != null) {
                transcriptDao.updateInPlace(
                    transcriptId = existing.transcriptId,
                    text = finalText,
                    language = asr.detectedLanguage,
                    confidence = asr.confidence,
                    asrProvider = asr.providerName,
                    createdAtMs = now
                )
            } else {
                val entity = TranscriptEntity(
                    transcriptId = UUID.randomUUID().toString(),
                    segmentId = segment.segmentId,
                    sessionId = segment.sessionId,
                    text = finalText,
                    language = asr.detectedLanguage,
                    startTimeMs = segment.startTimestampMs,
                    endTimeMs = segment.endTimestampMs,
                    confidence = asr.confidence,
                    speakerLabel = asr.speakerLabel,
                    asrProvider = asr.providerName,
                    createdAtMs = now
                )
                transcriptDao.upsert(entity)
            }
            syncedContentDao.updateAsrStatus(segment.segmentId, AsrProcessingStatus.COMPLETED, null)
            _progress.value = _progress.value.copy(
                lastProviderUsed = asr.providerName,
                lastError = null
            )

            // Only trigger immediate post-transcription audio deletion when confirmed non-empty text exists.
            // Blank / [Inaudible] clips always keep their audio preserved so the user can inspect or retry.
            if (hasSpeechText) {
                runCatching { audioRetentionManager.onTranscriptionConfirmed(segment.segmentId) }
            }

            if (hasSpeechText && settingsRepo.getSettings().autoExtractMemory) {
                memoryCoordinator.enqueuePendingExtraction()
            }
        }.onFailure { err ->
            // ASR failure must NEVER delete the synced encrypted audio.
            val msg = err.message ?: "Transcription failed"
            syncedContentDao.updateAsrStatus(segment.segmentId, AsrProcessingStatus.FAILED, msg)
            _progress.value = _progress.value.copy(lastError = msg)
        }
    }

    companion object {
        private const val TAG = "TranscriptionCoord"
        const val NO_SPEECH_PLACEHOLDER = "[Inaudible / No Speech Text]"
    }
}
