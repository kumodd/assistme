package com.personalai.primary.audio

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personalai.primary.data.db.SyncedContentDao
import com.personalai.primary.data.prefs.PrimarySettingsRepository
import com.personalai.primary.security.PrimaryEncryptedAudioStorage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioRetentionPolicy(
    val displayName: String,
    val shortLabel: String,
    val retentionDurationMs: Long?
) {
    AFTER_TRANSCRIPTION("Delete immediately after transcription", "After ASR", 0L),
    HOURS_1("Delete audio after 1 Hour", "1 Hr", TimeUnit.HOURS.toMillis(1)),
    HOURS_6("Delete audio after 6 Hours", "6 Hrs", TimeUnit.HOURS.toMillis(6)),
    HOURS_12("Delete audio after 12 Hours", "12 Hrs", TimeUnit.HOURS.toMillis(12)),
    DAYS_1("Delete audio after 1 Day", "1 Day", TimeUnit.DAYS.toMillis(1)),
    DAYS_3("Delete audio after 3 Days", "3 Days", TimeUnit.DAYS.toMillis(3)),
    DAYS_5("Delete audio after 5 Days", "5 Days", TimeUnit.DAYS.toMillis(5)),
    DAYS_7("Delete audio after 7 Days", "7 Days", TimeUnit.DAYS.toMillis(7)),
    KEEP_FOREVER("Keep all audio chunks", "Keep All", null);

    val isImmediateAfterTranscription: Boolean
        get() = this == AFTER_TRANSCRIPTION

    fun cutoffTimestampMs(nowMs: Long): Long? {
        val dur = retentionDurationMs ?: return null
        return nowMs - dur
    }
}

data class AudioCleanupReport(
    val deletedChunks: Int,
    val freedBytes: Long,
    val policy: AudioRetentionPolicy
)

/**
 * Manages immediate post-transcription, hourly (1h / 6h / 12h), daily (1d / 3d / 5d / 7d), and
 * manual encrypted audio chunk cleanup while strictly guaranteeing that raw audio is NEVER
 * auto-deleted until a non-empty transcript is verified persisted in Room.
 */
@Singleton
class AudioRetentionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncedContentDao: SyncedContentDao,
    private val audioStorage: PrimaryEncryptedAudioStorage,
    private val settingsRepo: PrimarySettingsRepository,
    private val playbackHelper: AudioPlaybackHelper,
    private val exportManager: AudioExportManager
) {
    private val cleanupMutex = Mutex()

    /**
     * Called right after a confirmed non-empty transcript has been saved to Room.
     * Deletes the local encrypted WAV immediately ONLY when `AFTER_TRANSCRIPTION` policy is active
     * and the segment is confirmed transcribed and not currently being played or exported.
     */
    suspend fun onTranscriptionConfirmed(segmentId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        withContext(Dispatchers.IO) {
            cleanupMutex.withLock {
                val policy = settingsRepo.getSettings().audioRetentionPolicy
                if (!policy.isImmediateAfterTranscription) return@withLock false
                if (!syncedContentDao.isSegmentVerifiedTranscribed(segmentId)) return@withLock false
                if (exportManager.isExporting(segmentId)) return@withLock false

                val seg = syncedContentDao.getSegment(segmentId) ?: return@withLock false
                if (seg.audioDeleted) return@withLock false
                if (playbackHelper.playingSegmentId.value == segmentId) {
                    // Defer deletion until next cleanup sweep while user is actively listening.
                    return@withLock false
                }
                val freed = audioStorage.deleteEncryptedAudio(seg.localEncryptedAudioPath)
                syncedContentDao.markAudioDeleted(segmentId, nowMs)
                Log.i(TAG, "Immediately pruned verified audio for seg #${seg.sequenceNumber} (${freed}B freed)")
                true
            }
        }

    suspend fun enforceRetentionPolicy(nowMs: Long = System.currentTimeMillis()): AudioCleanupReport =
        withContext(Dispatchers.IO) {
            cleanupMutex.withLock {
                val policy = settingsRepo.getSettings().audioRetentionPolicy
                if (policy == AudioRetentionPolicy.KEEP_FOREVER) {
                    return@withLock AudioCleanupReport(0, 0L, policy)
                }

                // Safety invariant: only auto-prune segments with confirmed non-empty transcripts in Room.
                val eligible = if (policy.isImmediateAfterTranscription) {
                    syncedContentDao.getAllVerifiedTranscribedAudioSegments()
                } else {
                    val cutoffMs = policy.cutoffTimestampMs(nowMs)
                        ?: return@withLock AudioCleanupReport(0, 0L, policy)
                    syncedContentDao.getRetentionEligibleAudioSegments(cutoffMs)
                }

                var deleted = 0
                var freed = 0L
                for (seg in eligible) {
                    if (exportManager.isExporting(seg.segmentId)) continue
                    if (playbackHelper.playingSegmentId.value == seg.segmentId) {
                        playbackHelper.stop()
                    }
                    freed += audioStorage.deleteEncryptedAudio(seg.localEncryptedAudioPath)
                    syncedContentDao.markAudioDeleted(seg.segmentId, nowMs)
                    deleted++
                }
                AudioCleanupReport(deletedChunks = deleted, freedBytes = freed, policy = policy)
            }
        }

    suspend fun deleteSpecificChunkAudio(segmentId: String): Result<Long> =
        withContext(Dispatchers.IO) {
            cleanupMutex.withLock {
                runCatching {
                    val seg = syncedContentDao.getSegment(segmentId)
                        ?: throw IllegalArgumentException("Audio chunk not found.")
                    if (exportManager.isExporting(segmentId)) {
                        throw IllegalStateException("Audio chunk is currently being exported. Try again in a moment.")
                    }
                    if (playbackHelper.playingSegmentId.value == segmentId) {
                        playbackHelper.stop()
                    }
                    val freed = audioStorage.deleteEncryptedAudio(seg.localEncryptedAudioPath)
                    syncedContentDao.markAudioDeleted(segmentId, System.currentTimeMillis())
                    freed
                }
            }
        }

    fun schedulePeriodicCleanup() {
        // Hourly schedule ensures 1 Hr / 6 Hrs / 12 Hrs retention policies execute promptly.
        val req = PeriodicWorkRequestBuilder<AudioRetentionWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }

    companion object {
        private const val TAG = "AudioRetentionManager"
        private const val WORK_NAME = "second_mind_audio_retention_v2"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioRetentionWorkerEntryPoint {
    fun audioRetentionManager(): AudioRetentionManager
}

class AudioRetentionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val ep = EntryPointAccessors.fromApplication(
                applicationContext,
                AudioRetentionWorkerEntryPoint::class.java
            )
            ep.audioRetentionManager().enforceRetentionPolicy()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
