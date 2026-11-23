package com.personalai.primary.audio

import android.content.Context
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
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioRetentionPolicy(
    val displayName: String,
    val shortLabel: String,
    val days: Int?
) {
    DAYS_1("Delete audio after 1 Day", "1 Day", 1),
    DAYS_3("Delete audio after 3 Days", "3 Days", 3),
    DAYS_5("Delete audio after 5 Days", "5 Days", 5),
    KEEP_FOREVER("Keep all audio chunks", "Keep All", null);

    fun cutoffTimestampMs(nowMs: Long): Long? {
        val d = days ?: return null
        return nowMs - TimeUnit.DAYS.toMillis(d.toLong())
    }
}

data class AudioCleanupReport(
    val deletedChunks: Int,
    val freedBytes: Long,
    val policy: AudioRetentionPolicy
)

/**
 * Manages automatic (1d / 3d / 5d) and on-demand encrypted audio chunk pruning while preserving
 * 100% of transcripts, extracted memories, entities, tasks, and summaries intact in Room.
 */
@Singleton
class AudioRetentionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncedContentDao: SyncedContentDao,
    private val audioStorage: PrimaryEncryptedAudioStorage,
    private val settingsRepo: PrimarySettingsRepository,
    private val playbackHelper: AudioPlaybackHelper
) {
    suspend fun enforceRetentionPolicy(nowMs: Long = System.currentTimeMillis()): AudioCleanupReport =
        withContext(Dispatchers.IO) {
            val policy = settingsRepo.getSettings().audioRetentionPolicy
            val cutoffMs = policy.cutoffTimestampMs(nowMs)
                ?: return@withContext AudioCleanupReport(0, 0L, policy)

            // Safety invariant: only auto-prune segments whose ASR already completed so raw speech is never lost.
            val eligible = syncedContentDao.getRetentionEligibleAudioSegments(cutoffMs)
            var deleted = 0
            var freed = 0L
            for (seg in eligible) {
                if (playbackHelper.playingSegmentId.value == seg.segmentId) {
                    playbackHelper.stop()
                }
                freed += audioStorage.deleteEncryptedAudio(seg.localEncryptedAudioPath)
                syncedContentDao.markAudioDeleted(seg.segmentId, nowMs)
                deleted++
            }
            AudioCleanupReport(deletedChunks = deleted, freedBytes = freed, policy = policy)
        }

    suspend fun deleteSpecificChunkAudio(segmentId: String): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val seg = syncedContentDao.getSegment(segmentId)
                    ?: throw IllegalArgumentException("Audio chunk not found.")
                if (playbackHelper.playingSegmentId.value == segmentId) {
                    playbackHelper.stop()
                }
                val freed = audioStorage.deleteEncryptedAudio(seg.localEncryptedAudioPath)
                syncedContentDao.markAudioDeleted(segmentId, System.currentTimeMillis())
                freed
            }
        }

    fun schedulePeriodicCleanup() {
        val req = PeriodicWorkRequestBuilder<AudioRetentionWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    companion object {
        private const val WORK_NAME = "second_mind_audio_retention_v1"
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
