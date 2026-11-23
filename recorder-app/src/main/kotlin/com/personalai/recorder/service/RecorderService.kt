package com.personalai.recorder.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.personalai.protocol.RecorderConfigPayload
import com.personalai.protocol.RecorderState
import com.personalai.recorder.audio.AudioCaptureEngine
import com.personalai.recorder.audio.CaptureEvent
import com.personalai.recorder.audio.SegmentCloseReason
import com.personalai.recorder.audio.SegmenterPhase
import com.personalai.recorder.audio.SpeechSegmenter
import com.personalai.recorder.data.db.DiagnosticSeverity
import com.personalai.recorder.data.db.SessionStatus
import com.personalai.recorder.data.prefs.RecorderPreferencesRepository
import com.personalai.recorder.data.repo.RecorderRepository
import com.personalai.recorder.domain.state.RecorderEvent
import com.personalai.recorder.domain.state.RecorderStateMachine
import com.personalai.recorder.health.DeviceHealthMonitor
import com.personalai.recorder.permissions.PermissionManager
import com.personalai.recorder.security.EncryptedSegmentStorage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class RecorderService : LifecycleService() {

    @Inject lateinit var stateMachine: RecorderStateMachine
    @Inject lateinit var audioCaptureEngine: AudioCaptureEngine
    @Inject lateinit var repository: RecorderRepository
    @Inject lateinit var prefs: RecorderPreferencesRepository
    @Inject lateinit var permissionManager: PermissionManager
    @Inject lateinit var segmentStorage: EncryptedSegmentStorage
    @Inject lateinit var healthMonitor: DeviceHealthMonitor
    @Inject lateinit var notificationHelper: NotificationHelper

    private var activeSessionId: String? = null
    private var speechSegmenter: SpeechSegmenter? = null
    private var captureEventsJob: Job? = null
    private var monitorJob: Job? = null
    private var currentConfig: RecorderConfigPayload = RecorderConfigPayload()
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        notificationHelper.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_EMERGENCY_STOP, ACTION_STOP_RECORDING -> {
                lifecycleScope.launch {
                    stopSessionAndSelf("USER_STOP")
                }
            }
            ACTION_STANDBY_PAUSE -> {
                lifecycleScope.launch {
                    pauseToStandby()
                }
            }
            ACTION_STANDBY_RESUME -> {
                lifecycleScope.launch {
                    resumeFromStandby()
                }
            }
            ACTION_ARM_AND_START, null -> {
                startArmedCapture()
            }
        }
        return START_NOT_STICKY
    }

    private fun startArmedCapture() {
        if (activeSessionId != null && stateMachine.snapshot.value.recorderState.isArmedOrCapturing) {
            return
        }
        stopping.set(false)

        if (!permissionManager.hasRecordAudioPermission()) {
            stateMachine.dispatch(RecorderEvent.PermissionsMissing)
            notificationHelper.postAlertNotification(
                "Microphone permission required",
                "Open AI Memory Recorder to grant microphone permission before recording."
            )
            stopSelf()
            return
        }

        val sessionId = UUID.randomUUID().toString()
        stateMachine.dispatch(RecorderEvent.ArmRequested(sessionId))

        val notification = notificationHelper.buildRecorderNotification(
            state = RecorderState.ARMED,
            segmentCount = 0,
            interruptionReason = null
        )

        try {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID_RECORDER,
                notification,
                fgsType
            )
        } catch (e: SecurityException) {
            lifecycleScope.launch {
                stateMachine.dispatch(RecorderEvent.PermissionsMissing)
                repository.logEvent("FGS", DiagnosticSeverity.ERROR, "SecurityException starting mic FGS: ${e.message}")
            }
            stopSelf()
            return
        } catch (e: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                lifecycleScope.launch {
                    stateMachine.dispatch(
                        RecorderEvent.FatalError("Background foreground-service launch blocked by Android (${e.message})")
                    )
                    repository.logEvent("FGS", DiagnosticSeverity.ERROR, "ForegroundServiceStartNotAllowedException: ${e.message}")
                }
            } else {
                lifecycleScope.launch {
                    stateMachine.dispatch(RecorderEvent.FatalError("Failed to enter foreground: ${e.message}"))
                }
            }
            stopSelf()
            return
        }

        lifecycleScope.launch {
            val settings = prefs.settingsFlow.first()
            if (settings.privacyKillSwitch) {
                stateMachine.dispatch(RecorderEvent.PrivacyDisabledChanged(true))
                stopSelf()
                return@launch
            }

            currentConfig = settings.config
            if (!segmentStorage.hasMinimumFreeSpace(currentConfig.minFreeStorageMb)) {
                stateMachine.dispatch(RecorderEvent.StorageExhausted)
                notificationHelper.postAlertNotification(
                    "Storage full",
                    "Recorder stopped safely because free storage is below ${currentConfig.minFreeStorageMb} MB."
                )
                stopSelf()
                return@launch
            }

            val health = healthMonitor.captureHealth()
            if (health.batteryPct in 0 until currentConfig.minBatteryPctToRecord && !health.isCharging) {
                stateMachine.dispatch(RecorderEvent.FatalError("Battery critically low (${health.batteryPct}%)"))
                notificationHelper.postAlertNotification(
                    "Battery critically low",
                    "Recorder stopped safely at ${health.batteryPct}% battery."
                )
                stopSelf()
                return@launch
            }

            activeSessionId = sessionId
            repository.ensureDeviceInitialized()
            repository.startSession(sessionId)

            stateMachine.dispatch(RecorderEvent.EngineStarting)
            speechSegmenter = SpeechSegmenter(sessionId = sessionId, config = currentConfig)

            val captureResult = audioCaptureEngine.startCapture(currentConfig)
            if (captureResult.isFailure) {
                val msg = captureResult.exceptionOrNull()?.message ?: "Unable to initialize AudioRecord"
                repository.closeSession(sessionId, SessionStatus.FAILED, msg)
                stateMachine.dispatch(RecorderEvent.FatalError(msg))
                activeSessionId = null
                stopSelf()
                return@launch
            }

            stateMachine.dispatch(RecorderEvent.ListeningStarted)
            refreshNotification()
            startEventCollector(sessionId)
            startWatchdogLoop(sessionId)
            observeConfigUpdates()
        }
    }

    private var configObserverJob: Job? = null

    private fun observeConfigUpdates() {
        configObserverJob?.cancel()
        configObserverJob = lifecycleScope.launch {
            prefs.settingsFlow.collect { settings ->
                if (settings.privacyKillSwitch) {
                    stopSessionAndSelf("PRIVACY_KILL_SWITCH")
                    return@collect
                }
                if (settings.config != currentConfig) {
                    currentConfig = settings.config
                    val sid = activeSessionId
                    if (sid != null && stateMachine.snapshot.value.recorderState.isActivelyListening) {
                        val pending = speechSegmenter?.flush(SegmentCloseReason.MANUAL_FLUSH)
                        if (pending != null) {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.persistEncryptedSpeechSegment(
                                        segment = pending,
                                        sampleRateHz = currentConfig.sampleRateHz,
                                        channels = currentConfig.channels
                                    )
                                }
                            }
                        }
                        speechSegmenter = SpeechSegmenter(sessionId = sid, config = currentConfig)
                    }
                    refreshNotification()
                }
            }
        }
    }

    private suspend fun pauseToStandby() {
        val currentState = stateMachine.snapshot.value.recorderState
        if (!currentState.isArmedOrCapturing || currentState == RecorderState.ARMED) return

        captureEventsJob?.cancel()
        audioCaptureEngine.stopCapture()
        val pending = speechSegmenter?.flush(SegmentCloseReason.MANUAL_FLUSH)
        val sid = activeSessionId
        if (pending != null && sid != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.persistEncryptedSpeechSegment(
                        segment = pending,
                        sampleRateHz = currentConfig.sampleRateHz,
                        channels = currentConfig.channels
                    )
                }
            }
        }
        stateMachine.dispatch(RecorderEvent.StandbyPaused)
        repository.logEvent("SESSION", DiagnosticSeverity.INFO, "Paused capture to ARMED standby via remote control")
        refreshNotification()
    }

    private suspend fun resumeFromStandby() {
        val currentState = stateMachine.snapshot.value.recorderState
        if (currentState != RecorderState.ARMED) return
        val sid = activeSessionId ?: return

        if (!permissionManager.hasRecordAudioPermission()) {
            stateMachine.dispatch(RecorderEvent.PermissionsMissing)
            stopSessionAndSelf("PERMISSION_REVOKED", keepState = true)
            return
        }

        val settings = prefs.settingsFlow.first()
        currentConfig = settings.config
        stateMachine.dispatch(RecorderEvent.EngineStarting)
        speechSegmenter = SpeechSegmenter(sessionId = sid, config = currentConfig)
        val captureResult = audioCaptureEngine.startCapture(currentConfig)
        if (captureResult.isFailure) {
            val msg = captureResult.exceptionOrNull()?.message ?: "Failed resuming AudioRecord"
            stateMachine.dispatch(RecorderEvent.FatalError(msg))
            stopSessionAndSelf("RESUME_FAILED", failedMessage = msg)
            return
        }
        stateMachine.dispatch(RecorderEvent.ListeningStarted)
        repository.logEvent("SESSION", DiagnosticSeverity.INFO, "Resumed capture from ARMED standby")
        refreshNotification()
        startEventCollector(sid)
    }

    private fun startEventCollector(sessionId: String) {
        captureEventsJob?.cancel()
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            lifecycleScope.launch(Dispatchers.IO) {
                repository.logEvent(
                    "COLLECTOR_ERROR",
                    DiagnosticSeverity.ERROR,
                    "Unexpected recorder loop error: ${throwable.message}"
                )
                stopSessionAndSelf("COLLECTOR_ERROR", failedMessage = throwable.message ?: "Unexpected error")
            }
        }
        captureEventsJob = lifecycleScope.launch(Dispatchers.Default + exceptionHandler) {
            audioCaptureEngine.events.collect { event ->
                when (event) {
                    is CaptureEvent.Frame -> {
                        val segmenter = speechSegmenter ?: return@collect
                        val step = segmenter.onFrame(
                            pcm16 = event.samples,
                            speechProbability = event.vadProbability,
                            frameStartTimestampMs = event.timestampMs
                        )
                        val telemetry = audioCaptureEngine.telemetry.value
                        stateMachine.updateTelemetry(
                            audioLevelDb = event.audioLevelDb,
                            vadProbability = event.vadProbability,
                            inputDeviceName = telemetry.inputDeviceName,
                            activeSegmentDurationMs = step.activeDurationMs
                        )

                        when (step.phase) {
                            SegmenterPhase.SPEECH_ACTIVE -> {
                                if (stateMachine.snapshot.value.recorderState != RecorderState.SPEECH_DETECTED) {
                                    stateMachine.dispatch(RecorderEvent.SpeechStarted)
                                    refreshNotification()
                                }
                            }
                            SegmenterPhase.TRAILING_SILENCE -> {
                                if (stateMachine.snapshot.value.recorderState != RecorderState.SILENCE) {
                                    stateMachine.dispatch(RecorderEvent.TrailingSilenceStarted)
                                }
                            }
                            SegmenterPhase.LISTENING -> {
                                if (stateMachine.snapshot.value.recorderState == RecorderState.SPEECH_DETECTED ||
                                    stateMachine.snapshot.value.recorderState == RecorderState.SILENCE
                                ) {
                                    stateMachine.dispatch(RecorderEvent.SegmentSealedBackToListening)
                                    refreshNotification()
                                }
                            }
                        }

                        step.completedSegment?.let { completed ->
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.persistEncryptedSpeechSegment(
                                        segment = completed,
                                        sampleRateHz = currentConfig.sampleRateHz,
                                        channels = currentConfig.channels
                                    )
                                }
                            }.onFailure { err ->
                                withContext(Dispatchers.IO) {
                                    repository.logEvent(
                                        "SEGMENT_SAVE_ERROR",
                                        DiagnosticSeverity.ERROR,
                                        "Failed saving segment ${completed.segmentId}: ${err.message}"
                                    )
                                }
                            }
                            refreshNotification()
                        }
                    }

                    is CaptureEvent.SilencedChanged -> {
                        if (event.isSilenced) {
                            val partial = speechSegmenter?.flush(SegmentCloseReason.INTERRUPTED)
                            if (partial != null) {
                                withContext(Dispatchers.IO) {
                                    repository.persistEncryptedSpeechSegment(
                                        segment = partial,
                                        sampleRateHz = currentConfig.sampleRateHz,
                                        channels = currentConfig.channels
                                    )
                                }
                            }
                            repository.recordInterruption(sessionId, "Mic silenced (${event.sourceDesc})")
                            stateMachine.dispatch(RecorderEvent.AudioSilenced("System silenced microphone"))
                        } else {
                            if (permissionManager.hasRecordAudioPermission() &&
                                stateMachine.snapshot.value.recorderState == RecorderState.AUDIO_SILENCED
                            ) {
                                stateMachine.dispatch(RecorderEvent.AudioUnsilenced)
                                repository.logEvent(
                                    "AUDIO_RECOVERY",
                                    DiagnosticSeverity.INFO,
                                    "Microphone capture unsilenced and resumed"
                                )
                            }
                        }
                        refreshNotification()
                    }

                    is CaptureEvent.InputDeviceChanged -> {
                        repository.logEvent(
                            "AUDIO_DEVICE",
                            DiagnosticSeverity.INFO,
                            "Input device changed to ${event.deviceName}"
                        )
                    }

                    is CaptureEvent.OverrunDetected -> {
                        repository.logEvent(
                            "AUDIO_OVERRUN",
                            DiagnosticSeverity.WARNING,
                            "Detected audio buffer read delay / overrun"
                        )
                    }

                    is CaptureEvent.CaptureError -> {
                        repository.logEvent(
                            "AUDIO_ERROR",
                            DiagnosticSeverity.ERROR,
                            "AudioRecord failure (${event.code}): ${event.message}"
                        )
                        stopSessionAndSelf("AUDIO_ERROR_${event.code}", failedMessage = event.message)
                    }
                }
            }
        }
    }

    private fun startWatchdogLoop(sessionId: String) {
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !stopping.get()) {
                delay(15_000L)
                repository.updateHeartbeat(sessionId)

                if (!permissionManager.hasRecordAudioPermission()) {
                    repository.logEvent("PERMISSION", DiagnosticSeverity.ERROR, "RECORD_AUDIO permission revoked mid-session")
                    stateMachine.dispatch(RecorderEvent.PermissionsMissing)
                    notificationHelper.postAlertNotification(
                        "Microphone permission revoked",
                        "Recording stopped because microphone access was revoked."
                    )
                    stopSessionAndSelf("PERMISSION_REVOKED", keepState = true)
                    break
                }

                if (!segmentStorage.hasMinimumFreeSpace(currentConfig.minFreeStorageMb)) {
                    repository.logEvent("STORAGE", DiagnosticSeverity.ERROR, "Storage below safety threshold; stopping safely")
                    stateMachine.dispatch(RecorderEvent.StorageExhausted)
                    notificationHelper.postAlertNotification(
                        "Storage full",
                        "Recording stopped safely to protect existing encrypted segments."
                    )
                    stopSessionAndSelf("STORAGE_FULL", keepState = true)
                    break
                }

                val health = healthMonitor.captureHealth()
                if (health.batteryPct in 0 until currentConfig.minBatteryPctToRecord && !health.isCharging) {
                    repository.logEvent(
                        "BATTERY",
                        DiagnosticSeverity.WARNING,
                        "Battery critically low (${health.batteryPct}%); stopping recorder safely"
                    )
                    notificationHelper.postAlertNotification(
                        "Battery critically low (${health.batteryPct}%)",
                        "Recorder stopped safely to avoid corrupted storage."
                    )
                    stopSessionAndSelf("LOW_BATTERY", failedMessage = "Battery critically low (${health.batteryPct}%)")
                    break
                }
            }
        }
    }

    private suspend fun stopSessionAndSelf(
        reason: String,
        failedMessage: String? = null,
        keepState: Boolean = false
    ) {
        if (stopping.getAndSet(true)) return

        if (!keepState) {
            stateMachine.dispatch(RecorderEvent.StopRequested)
        }
        monitorJob?.cancel()
        configObserverJob?.cancel()
        captureEventsJob?.cancel()
        audioCaptureEngine.stopCapture()

        val sessionId = activeSessionId
        activeSessionId = null
        val pending = speechSegmenter?.flush(SegmentCloseReason.MANUAL_FLUSH)
        speechSegmenter = null

        withContext(Dispatchers.IO) {
            if (pending != null && sessionId != null) {
                runCatching {
                    repository.persistEncryptedSpeechSegment(
                        segment = pending,
                        sampleRateHz = currentConfig.sampleRateHz,
                        channels = currentConfig.channels
                    )
                }
            }
            if (sessionId != null) {
                val endStatus = when {
                    failedMessage != null -> SessionStatus.FAILED
                    reason == "STORAGE_FULL" || reason == "PERMISSION_REVOKED" -> SessionStatus.INTERRUPTED
                    else -> SessionStatus.COMPLETED
                }
                repository.closeSession(sessionId, endStatus, reason)
            }
        }

        if (!keepState) {
            if (failedMessage != null) {
                stateMachine.dispatch(RecorderEvent.FatalError(failedMessage))
            } else {
                stateMachine.dispatch(RecorderEvent.StoppedToIdle)
            }
        }

        withContext(Dispatchers.Main) {
            ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun refreshNotification() {
        val snap = stateMachine.snapshot.value
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NotificationHelper.NOTIFICATION_ID_RECORDER,
            notificationHelper.buildRecorderNotification(
                state = snap.recorderState,
                segmentCount = snap.sessionSegmentsCount,
                interruptionReason = snap.interruptionReason
            )
        )
    }

    override fun onDestroy() {
        isServiceRunning = false
        audioCaptureEngine.stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ARM_AND_START = "com.personalai.recorder.action.ARM_AND_START"
        const val ACTION_STOP_RECORDING = "com.personalai.recorder.action.STOP_RECORDING"
        const val ACTION_EMERGENCY_STOP = "com.personalai.recorder.action.EMERGENCY_STOP"
        const val ACTION_STANDBY_PAUSE = "com.personalai.recorder.action.STANDBY_PAUSE"
        const val ACTION_STANDBY_RESUME = "com.personalai.recorder.action.STANDBY_RESUME"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun startArmed(context: Context) {
            val intent = Intent(context, RecorderService::class.java).apply {
                action = ACTION_ARM_AND_START
            }
            context.startForegroundService(intent)
        }

        fun stopEmergency(context: Context) {
            val intent = Intent(context, RecorderService::class.java).apply {
                action = ACTION_EMERGENCY_STOP
            }
            context.startService(intent)
        }

        fun pauseToStandby(context: Context) {
            val intent = Intent(context, RecorderService::class.java).apply {
                action = ACTION_STANDBY_PAUSE
            }
            context.startService(intent)
        }

        fun resumeFromStandby(context: Context) {
            val intent = Intent(context, RecorderService::class.java).apply {
                action = ACTION_STANDBY_RESUME
            }
            context.startService(intent)
        }
    }
}
