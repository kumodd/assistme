package com.personalai.recorder.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personalai.protocol.AudioSourcePreset
import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.RecorderConfigPayload
import com.personalai.protocol.RecorderState
import com.personalai.recorder.audio.AudioCaptureEngine
import com.personalai.recorder.audio.LiveAudioTelemetry
import com.personalai.recorder.ble.BleHostRuntimeState
import com.personalai.recorder.ble.BlePeripheralManager
import com.personalai.recorder.ble.BlePeripheralService
import com.personalai.recorder.ble.PairingCredentialsStore
import com.personalai.recorder.ble.PairingUiSnapshot
import com.personalai.recorder.data.db.AudioSegmentEntity
import com.personalai.recorder.data.db.DiagnosticEventEntity
import com.personalai.recorder.data.db.DiagnosticSeverity
import com.personalai.recorder.data.db.RecorderDeviceEntity
import com.personalai.recorder.data.db.RecordingSessionEntity
import com.personalai.recorder.data.prefs.RecorderAppSettings
import com.personalai.recorder.data.prefs.RecorderPreferencesRepository
import com.personalai.recorder.data.repo.RecorderRepository
import com.personalai.recorder.domain.state.RecorderEvent
import com.personalai.recorder.domain.state.RecorderSnapshot
import com.personalai.recorder.domain.state.RecorderStateMachine
import com.personalai.recorder.health.DeviceHealthMonitor
import com.personalai.recorder.health.DeviceHealthSnapshot
import com.personalai.recorder.permissions.PermissionManager
import com.personalai.recorder.permissions.PermissionSnapshot
import com.personalai.recorder.security.MasterKeyProvider
import com.personalai.recorder.service.RecorderService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecorderUiState(
    val snapshot: RecorderSnapshot = RecorderSnapshot(),
    val bleState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val settings: RecorderAppSettings = RecorderAppSettings(),
    val permissions: PermissionSnapshot,
    val health: DeviceHealthSnapshot,
    val device: RecorderDeviceEntity? = null,
    val latestSession: RecordingSessionEntity? = null,
    val recentSegments: List<AudioSegmentEntity> = emptyList(),
    val totalSegmentCount: Int = 0,
    val totalStoredBytes: Long = 0L,
    val pendingSyncCount: Int = 0,
    val diagnostics: List<DiagnosticEventEntity> = emptyList(),
    val micTestRunning: Boolean = false,
    val micTestTelemetry: LiveAudioTelemetry = LiveAudioTelemetry(),
    val keystoreReady: Boolean = false,
    val integrityCheckMessage: String? = null,
    val bleHostState: BleHostRuntimeState = BleHostRuntimeState(),
    val pairingState: PairingUiSnapshot = PairingUiSnapshot()
)

@HiltViewModel
class RecorderViewModel @Inject constructor(
    application: Application,
    private val stateMachine: RecorderStateMachine,
    private val repository: RecorderRepository,
    private val prefs: RecorderPreferencesRepository,
    private val permissionManager: PermissionManager,
    private val healthMonitor: DeviceHealthMonitor,
    private val audioCaptureEngine: AudioCaptureEngine,
    private val masterKeyProvider: MasterKeyProvider,
    private val blePeripheralManager: BlePeripheralManager,
    private val pairingCredentialsStore: PairingCredentialsStore
) : AndroidViewModel(application) {

    private val _permissions = MutableStateFlow(permissionManager.getSnapshot())
    private val _health = MutableStateFlow(healthMonitor.captureHealth())
    private val _micTestRunning = MutableStateFlow(false)
    private val _keystoreReady = MutableStateFlow(false)
    private val _integrityMessage = MutableStateFlow<String?>(null)

    private val coreFlows = combine(
        stateMachine.snapshot,
        stateMachine.bleState,
        prefs.settingsFlow,
        _permissions,
        _health
    ) { snap, ble, settings, perms, health ->
        CoreStateTuple(snap, ble, settings, perms, health)
    }

    private val storageFlows = combine(
        repository.observeDevice(),
        repository.observeLatestSession(),
        repository.observeRecentSegments(50),
        repository.observeTotalSegmentCount(),
        repository.observeTotalStoredBytes()
    ) { dev, latestSession, segments, count, bytes ->
        StorageStateTuple(dev, latestSession, segments, count, bytes)
    }

    private val extraFlows = combine(
        repository.observePendingSyncCount(),
        repository.observeDiagnosticEvents(100),
        _micTestRunning,
        audioCaptureEngine.telemetry,
        _keystoreReady
    ) { pending, events, testRunning, telemetry, ksReady ->
        ExtraStateTuple(pending, events, testRunning, telemetry, ksReady)
    }

    val uiState: StateFlow<RecorderUiState> = combine(
        coreFlows,
        storageFlows,
        extraFlows,
        _integrityMessage,
        combine(blePeripheralManager.hostState, pairingCredentialsStore.uiState) { h, p -> h to p }
    ) { core, storage, extra, integrityMsg, (hostState, pairingState) ->
        RecorderUiState(
            snapshot = core.snap,
            bleState = core.ble,
            settings = core.settings,
            permissions = core.perms,
            health = core.health,
            device = storage.device,
            latestSession = storage.latestSession,
            recentSegments = storage.segments,
            totalSegmentCount = storage.totalCount,
            totalStoredBytes = storage.totalBytes,
            pendingSyncCount = extra.pendingSync,
            diagnostics = extra.diagnostics,
            micTestRunning = extra.micTestRunning,
            micTestTelemetry = extra.telemetry,
            keystoreReady = extra.keystoreReady,
            integrityCheckMessage = integrityMsg,
            bleHostState = hostState,
            pairingState = pairingState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = RecorderUiState(
            permissions = _permissions.value,
            health = _health.value
        )
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.reconcileUncleanShutdownIfAny(RecorderService.isServiceRunning)
            val initialized = runCatching { repository.ensureDeviceInitialized() }.isSuccess
            _keystoreReady.value = initialized && masterKeyProvider.isHardwareBackedKeyReady()
            refreshPermissionsAndHealth()
        }

        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5_000L)
                _health.value = healthMonitor.captureHealth()
            }
        }
    }

    fun refreshPermissionsAndHealth() {
        val perms = permissionManager.getSnapshot()
        _permissions.value = perms
        _health.value = healthMonitor.captureHealth()

        val currentState = stateMachine.snapshot.value.recorderState
        if (!perms.canRecordAudio && currentState != RecorderState.UNCONFIGURED) {
            stateMachine.dispatch(RecorderEvent.PermissionsMissing)
        } else if (perms.canRecordAudio && currentState == RecorderState.PERMISSION_REQUIRED) {
            stateMachine.dispatch(RecorderEvent.SetupReady)
        }
    }

    fun initializeStorageAndIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureDeviceInitialized()
            _keystoreReady.value = masterKeyProvider.isHardwareBackedKeyReady()
            refreshPermissionsAndHealth()
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            repository.ensureDeviceInitialized()
            _keystoreReady.value = masterKeyProvider.isHardwareBackedKeyReady()
            prefs.setOnboardingCompleted(true)
            if (permissionManager.hasRecordAudioPermission()) {
                stateMachine.dispatch(RecorderEvent.SetupReady)
            } else {
                stateMachine.dispatch(RecorderEvent.PermissionsMissing)
            }
        }
    }

    fun reopenOnboarding() {
        viewModelScope.launch {
            prefs.setOnboardingCompleted(false)
        }
    }

    fun startMicTest() {
        if (RecorderService.isServiceRunning || stateMachine.snapshot.value.recorderState.isArmedOrCapturing) {
            return
        }
        if (!permissionManager.hasRecordAudioPermission()) {
            stateMachine.dispatch(RecorderEvent.PermissionsMissing)
            return
        }
        val currentConfig = uiState.value.settings.config
        val res = audioCaptureEngine.startCapture(currentConfig)
        _micTestRunning.value = res.isSuccess
        if (res.isFailure) {
            viewModelScope.launch {
                repository.logEvent(
                    "MIC_TEST",
                    DiagnosticSeverity.ERROR,
                    "Mic test failed: ${res.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun stopMicTest() {
        if (!RecorderService.isServiceRunning) {
            audioCaptureEngine.stopCapture()
        }
        _micTestRunning.value = false
    }

    fun armAndStartRecording() {
        stopMicTest()
        refreshPermissionsAndHealth()
        if (!permissionManager.hasRecordAudioPermission()) {
            stateMachine.dispatch(RecorderEvent.PermissionsMissing)
            return
        }
        RecorderService.startArmed(getApplication())
    }

    fun emergencyStop() {
        stopMicTest()
        if (RecorderService.isServiceRunning) {
            RecorderService.stopEmergency(getApplication())
        } else {
            stateMachine.dispatch(RecorderEvent.AcknowledgeNonFatalState)
        }
    }

    fun acknowledgeInterruption() {
        stateMachine.dispatch(RecorderEvent.AcknowledgeNonFatalState)
    }

    fun updateConfig(config: RecorderConfigPayload) {
        viewModelScope.launch {
            prefs.updateConfig(config)
            repository.logEvent(
                "CONFIG",
                DiagnosticSeverity.INFO,
                "Updated VAD threshold=${config.vadThreshold}, silenceMs=${config.silenceTimeoutMs}, maxSec=${config.maxSegmentDurationMs / 1000}"
            )
        }
    }

    fun setAudioSource(preset: AudioSourcePreset) {
        val current = uiState.value.settings.config
        updateConfig(current.copy(audioSource = preset))
    }

    fun setPrivacyKillSwitch(disabled: Boolean) {
        viewModelScope.launch {
            if (disabled && RecorderService.isServiceRunning) {
                RecorderService.stopEmergency(getApplication())
            }
            prefs.setPrivacyKillSwitch(disabled)
            stateMachine.dispatch(RecorderEvent.PrivacyDisabledChanged(disabled))
            repository.logEvent(
                "PRIVACY",
                DiagnosticSeverity.INFO,
                "Privacy kill-switch set to $disabled"
            )
        }
    }

    fun setAutoDeleteSynced(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAutoDeleteSynced(enabled)
        }
    }

    fun setLocalOnlyPauseSync(paused: Boolean) {
        val current = uiState.value.settings.config
        updateConfig(current.copy(localOnlyPauseSync = paused))
    }

    fun startBleHost() {
        BlePeripheralService.start(getApplication())
    }

    fun stopBleHost() {
        BlePeripheralService.stop(getApplication())
    }

    fun togglePairingWindow(open: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val dev = repository.ensureDeviceInitialized()
            if (open) {
                if (!BlePeripheralService.isHostRunning) {
                    BlePeripheralService.start(getApplication())
                }
                pairingCredentialsStore.openPairingWindow(dev.deviceId, dev.deviceName)
            } else {
                pairingCredentialsStore.closePairingWindow()
            }
        }
    }

    fun approvePendingPairing() {
        blePeripheralManager.approvePendingPairingOnUi()
    }

    fun rejectPendingPairing() {
        blePeripheralManager.rejectPendingPairingOnUi()
    }

    fun unpairDevice() {
        blePeripheralManager.unpairDevice()
    }

    fun verifySegment(segmentId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repository.verifySegmentIntegrity(segmentId)
            _integrityMessage.value = if (ok) {
                "Verified encrypted segment $segmentId (AES-GCM + SHA-256 OK)"
            } else {
                "Segment $segmentId failed verification and was quarantined"
            }
        }
    }

    fun clearIntegrityMessage() {
        _integrityMessage.value = null
    }

    fun wipeAllRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            if (RecorderService.isServiceRunning) {
                RecorderService.stopEmergency(getApplication())
            }
            repository.wipeAllRecordingsAndLogs()
            _integrityMessage.value = "All local encrypted recordings deleted."
        }
    }

    fun createBatteryOptimizationIntent(): Intent = permissionManager.createBatteryOptimizationIntent()
    fun createAppDetailsIntent(): Intent = permissionManager.createAppDetailsIntent()
    fun requiredPermissions(): Array<String> = permissionManager.requiredRuntimePermissions().toTypedArray()

    override fun onCleared() {
        if (_micTestRunning.value && !RecorderService.isServiceRunning) {
            audioCaptureEngine.stopCapture()
        }
        super.onCleared()
    }

    private data class CoreStateTuple(
        val snap: RecorderSnapshot,
        val ble: BleConnectionState,
        val settings: RecorderAppSettings,
        val perms: PermissionSnapshot,
        val health: DeviceHealthSnapshot
    )

    private data class StorageStateTuple(
        val device: RecorderDeviceEntity?,
        val latestSession: RecordingSessionEntity?,
        val segments: List<AudioSegmentEntity>,
        val totalCount: Int,
        val totalBytes: Long
    )

    private data class ExtraStateTuple(
        val pendingSync: Int,
        val diagnostics: List<DiagnosticEventEntity>,
        val micTestRunning: Boolean,
        val telemetry: LiveAudioTelemetry,
        val keystoreReady: Boolean
    )
}
