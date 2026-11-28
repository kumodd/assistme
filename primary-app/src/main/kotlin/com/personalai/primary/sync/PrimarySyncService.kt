package com.personalai.primary.sync

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personalai.primary.MainActivity
import com.personalai.primary.asr.AsrProgressSnapshot
import com.personalai.primary.asr.TranscriptionCoordinator
import com.personalai.primary.audio.AudioRetentionManager
import com.personalai.primary.ble.BleClientController
import com.personalai.primary.data.db.PairedRecorderDao
import com.personalai.primary.data.prefs.AppDeviceRole
import com.personalai.primary.data.prefs.PrimarySettingsRepository
import com.personalai.primary.memory.MemoryCoordinator
import com.personalai.protocol.BleConnectionState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the Primary device's Bluetooth sync, auto-reconnect, offline transcription, and retention
 * pipeline alive reliably when the UI is minimized or swiped away (`foregroundServiceType="connectedDevice"`).
 */
@AndroidEntryPoint
class PrimarySyncService : LifecycleService() {

    @Inject lateinit var bleClient: BleClientController
    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var transcriptionCoordinator: TranscriptionCoordinator
    @Inject lateinit var audioRetentionManager: AudioRetentionManager
    @Inject lateinit var pairedDao: PairedRecorderDao
    @Inject lateinit var settingsRepo: PrimarySettingsRepository

    private var observerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastNotificationUpdateMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_LINK -> {
                bleClient.disconnect(userInitiated = true)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SYNC_NOW -> {
                syncManager.triggerSyncNow()
                return START_STICKY
            }
        }

        val initialNotif = buildNotification(
            bleState = bleClient.bleState.value,
            peerName = null,
            sync = syncManager.progress.value,
            asr = transcriptionCoordinator.progress.value
        )

        try {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotif, fgsType)
        } catch (t: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && t is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Background FGS start blocked by Android: ${t.message}")
            } else {
                Log.w(TAG, "Failed starting PrimarySyncService foreground: ${t.message}")
            }
            stopSelf()
            return START_NOT_STICKY
        }

        lifecycleScope.launch {
            val role = settingsRepo.getDeviceRole()
            val settings = settingsRepo.getSettings()
            val paired = pairedDao.getPairedDevice()
            if (role != AppDeviceRole.PRIMARY || !settings.keepBackgroundLinkAlive || paired == null) {
                ServiceCompat.stopForeground(this@PrimarySyncService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            bleClient.ensureConnectedToPaired()
            if (settings.autoTranscribeSynced) {
                transcriptionCoordinator.enqueuePendingProcessing()
            }
            startNotificationObserver()
            startMaintenanceLoop()
        }

        return START_STICKY
    }

    private fun startNotificationObserver() {
        observerJob?.cancel()
        observerJob = lifecycleScope.launch {
            combine(
                bleClient.bleState,
                pairedDao.observePairedDevice(),
                syncManager.progress,
                transcriptionCoordinator.progress
            ) { ble, paired, sync, asr ->
                NotifSnapshot(
                    ble = ble,
                    peerName = paired?.deviceName,
                    isSyncing = sync.isSyncing,
                    syncSeq = sync.activeSegmentSeq,
                    isTranscribing = asr.isTranscribing,
                    asrSeq = asr.activeSegmentSequence
                )
            }
                .distinctUntilChanged()
                .collect { snap ->
                    val now = SystemClock.elapsedRealtime()
                    val critical = snap.ble == BleConnectionState.READY ||
                        snap.ble == BleConnectionState.DISCONNECTED ||
                        snap.isSyncing || snap.isTranscribing
                    if (!critical && now - lastNotificationUpdateMs < 600L) return@collect
                    lastNotificationUpdateMs = now
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            bleState = snap.ble,
                            peerName = snap.peerName,
                            sync = syncManager.progress.value,
                            asr = transcriptionCoordinator.progress.value
                        )
                    )
                }
        }
    }

    private fun startMaintenanceLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                delay(60_000L)
                val role = settingsRepo.getDeviceRole()
                val settings = settingsRepo.getSettings()
                val paired = pairedDao.getPairedDevice()
                if (role != AppDeviceRole.PRIMARY || !settings.keepBackgroundLinkAlive || paired == null) {
                    ServiceCompat.stopForeground(this@PrimarySyncService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                if (bleClient.bleState.value == BleConnectionState.DISCONNECTED) {
                    bleClient.ensureConnectedToPaired()
                } else if (bleClient.bleState.value == BleConnectionState.READY && settings.autoSyncOnReconnect) {
                    // Safety poll: catches newly recorded segments even if a BLE status notify was dropped.
                    val remote = bleClient.remoteStatus.value
                    if (remote == null || (!remote.localOnlyPauseSync && remote.pendingSyncSegments > 0)) {
                        syncManager.triggerSyncNow()
                    } else {
                        bleClient.pingQuietly()
                    }
                }
                runCatching { audioRetentionManager.enforceRetentionPolicy() }
            }
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sunbo Background Link",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps encrypted Bluetooth sync and offline transcription reliable in the background"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(
        bleState: BleConnectionState,
        peerName: String?,
        sync: SyncProgressSnapshot,
        asr: AsrProgressSnapshot
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, PrimarySyncService::class.java).apply { action = ACTION_STOP_LINK },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val syncIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, PrimarySyncService::class.java).apply { action = ACTION_SYNC_NOW },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when {
            sync.isSyncing -> "Sunbo • Syncing Audio"
            asr.isTranscribing -> "Sunbo • Transcribing Offline"
            bleState == BleConnectionState.READY -> "Sunbo Link Active"
            bleState == BleConnectionState.CONNECTING || bleState == BleConnectionState.AUTHENTICATING ->
                "Sunbo • Reconnecting..."
            else -> "Sunbo • Waiting for Recorder"
        }

        val detail = when {
            sync.isSyncing -> "Receiving segment #${sync.activeSegmentSeq ?: "-"} via ${sync.transportLabel ?: "BLE"}"
            asr.isTranscribing -> "Offline speech recognition on segment #${asr.activeSegmentSequence ?: "-"}"
            bleState == BleConnectionState.READY -> "Connected to ${peerName ?: "Secondary Recorder"} • Auto-sync ready"
            else -> "Auto-reconnecting to ${peerName ?: "Secondary Recorder"} when nearby"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent)

        if (bleState == BleConnectionState.READY && !sync.isSyncing) {
            builder.addAction(android.R.drawable.ic_popup_sync, "Sync Now", syncIntent)
        }
        builder.addAction(android.R.drawable.ic_media_pause, "Pause Link", stopIntent)
        return builder.build()
    }

    override fun onDestroy() {
        isServiceRunning = false
        observerJob?.cancel()
        heartbeatJob?.cancel()
        super.onDestroy()
    }

    private data class NotifSnapshot(
        val ble: BleConnectionState,
        val peerName: String?,
        val isSyncing: Boolean,
        val syncSeq: Long?,
        val isTranscribing: Boolean,
        val asrSeq: Long?
    )

    companion object {
        private const val TAG = "PrimarySyncService"
        private const val CHANNEL_ID = "second_mind_sync_link"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START_LINK = "com.personalai.primary.action.START_SYNC_LINK"
        const val ACTION_STOP_LINK = "com.personalai.primary.action.STOP_SYNC_LINK"
        const val ACTION_SYNC_NOW = "com.personalai.primary.action.SYNC_NOW"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, PrimarySyncService::class.java).apply {
                action = ACTION_START_LINK
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "Unable to start PrimarySyncService: ${it.message}") }
        }

        fun stop(context: Context) {
            if (!isServiceRunning) return
            val intent = Intent(context, PrimarySyncService::class.java).apply {
                action = ACTION_STOP_LINK
            }
            runCatching { context.startService(intent) }
        }

        fun enqueueOfflineProcessingFallback(context: Context) {
            val req = OneTimeWorkRequestBuilder<PendingProcessingWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "second_mind_pending_processing_v1",
                ExistingWorkPolicy.KEEP,
                req
            )
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PendingProcessingEntryPoint {
    fun settingsRepository(): PrimarySettingsRepository
    fun transcriptionCoordinator(): TranscriptionCoordinator
    fun memoryCoordinator(): MemoryCoordinator
    fun audioRetentionManager(): AudioRetentionManager
}

/**
 * WorkManager safety net that resumes interrupted offline ASR, memory extraction, and audio
 * retention cleanup if the Primary process was terminated mid-queue.
 */
class PendingProcessingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val ep = EntryPointAccessors.fromApplication(
                applicationContext,
                PendingProcessingEntryPoint::class.java
            )
            if (ep.settingsRepository().getDeviceRole() != AppDeviceRole.PRIMARY) {
                return Result.success()
            }
            val settings = ep.settingsRepository().getSettings()
            if (settings.autoTranscribeSynced) {
                ep.transcriptionCoordinator().processPendingQueue()
            }
            if (settings.autoExtractMemory) {
                ep.memoryCoordinator().extractPendingTranscripts()
            }
            ep.audioRetentionManager().enforceRetentionPolicy()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
