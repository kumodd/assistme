package com.personalai.primary

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalai.primary.data.prefs.AppDeviceRole
import com.personalai.primary.ui.AppRoleViewModel
import com.personalai.primary.ui.PrimaryViewModel
import com.personalai.primary.ui.screens.PrimaryAppShell
import com.personalai.primary.ui.screens.RoleSelectionScreen
import com.personalai.primary.ui.theme.PrimaryTheme
import com.personalai.primary.ui.theme.SecondMindColors
import com.personalai.recorder.ui.RecorderViewModel
import com.personalai.recorder.ui.screens.OnboardingFlowScreen
import com.personalai.recorder.ui.screens.RecorderOperationalShell
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val roleViewModel: AppRoleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            val role by roleViewModel.selectedRole.collectAsStateWithLifecycle()

            PrimaryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SecondMindColors.Canvas
                ) {
                    when (role) {
                        null -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(SecondMindColors.Canvas),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(SecondMindColors.HeroCoral),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Psychology,
                                        contentDescription = "Sunbo",
                                        tint = Color.White,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                                Text(
                                    text = "Sunbo",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SecondMindColors.PrimaryInk
                                )
                                Text(
                                    text = "Your 2nd Mind",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SecondMindColors.SecondaryInk
                                )
                            }
                        }
                        AppDeviceRole.UNSELECTED -> RoleSelectionScreen(
                            onSelectPrimary = { roleViewModel.selectRole(AppDeviceRole.PRIMARY) },
                            onSelectSecondary = { roleViewModel.selectRole(AppDeviceRole.SECONDARY) }
                        )
                        AppDeviceRole.PRIMARY -> PrimaryModeHost(
                            onStartActivity = { intent -> runCatching { startActivity(intent) } },
                            onSwitchToSecondary = {
                                roleViewModel.selectRole(AppDeviceRole.SECONDARY)
                            }
                        )
                        AppDeviceRole.SECONDARY -> SecondaryModeHost(
                            onStartActivity = { intent -> runCatching { startActivity(intent) } },
                            onSwitchToPrimary = {
                                roleViewModel.selectRole(AppDeviceRole.PRIMARY)
                            },
                            onOpenRolePicker = {
                                roleViewModel.selectRole(AppDeviceRole.UNSELECTED)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryModeHost(
    onStartActivity: (Intent) -> Unit,
    onSwitchToSecondary: () -> Unit,
    viewModel: PrimaryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissions()
    }
    val uoaiRegistrationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onUoaiRegistrationResult(result.resultCode, result.data)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
                viewModel.refreshUoaiConnection()
                viewModel.refreshOfflineSpeechPacks()
                viewModel.ensureBackgroundLinkIfNeeded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    PrimaryAppShell(
        uiState = uiState,
        playbackProgressFlow = viewModel.playbackProgressFlow,
        onRequestPermissions = {
            permissionLauncher.launch(viewModel.requiredPermissions())
        },
        onOpenAppSettings = {
            onStartActivity(viewModel.appDetailsIntent())
        },
        onStartScan = viewModel::startScan,
        onStopScan = viewModel::stopScan,
        onBeginPairing = viewModel::beginPairing,
        onConfirmSas = viewModel::confirmSas,
        onCancelSas = viewModel::cancelSas,
        onConnectPaired = viewModel::connectPaired,
        onDisconnect = viewModel::disconnect,
        onUnpair = viewModel::unpair,
        onStartRecording = viewModel::startRecording,
        onStopRecording = viewModel::stopRecording,
        onRefreshAll = viewModel::refreshAll,
        onPing = viewModel::ping,
        onSyncNow = viewModel::syncNow,
        onSetConfig = viewModel::setConfig,
        onSetLocalOnlyPauseSync = viewModel::setLocalOnlyPauseSync,
        onClearFeedback = viewModel::clearFeedback,
        onClearError = viewModel::clearLastError,
        onSearchQueryChange = viewModel::setSearchQuery,
        onLanguageFilterChange = viewModel::setLanguageFilter,
        onToggleAudioPlayback = viewModel::toggleAudioPlayback,
        onRetrySegmentAsr = viewModel::retrySegmentAsr,
        onRetryAllFailedAsr = viewModel::retryAllFailedAsr,
        onRunPendingAsrNow = viewModel::runPendingAsrNow,
        onRefreshOfflineSpeechPacks = viewModel::refreshOfflineSpeechPacks,
        onDownloadOfflineSpeechPacks = viewModel::downloadOfflineSpeechPacks,
        onDownloadSpecificChunk = viewModel::downloadSpecificChunk,
        onShareExportedAudio = { exported ->
            onStartActivity(viewModel.buildShareExportedIntent(exported))
        },
        onDeleteSpecificChunkAudio = viewModel::deleteSpecificChunkAudio,
        onSetAudioRetentionPolicy = viewModel::setAudioRetentionPolicy,
        onPurgeExpiredAudioNow = viewModel::purgeExpiredAudioNow,
        onClearStorageNotice = viewModel::clearStorageNotice,
        onClearExportFeedback = viewModel::clearExportFeedback,
        onAskQuestion = viewModel::askAi,
        onStopAskAi = viewModel::stopAskAi,
        onLaunchUoaiRegistration = { regIntent ->
            uoaiRegistrationLauncher.launch(regIntent)
        },
        onOpenUoaiProvider = { launchIntent ->
            launchIntent?.let(onStartActivity)
        },
        onRefreshUoaiConnection = viewModel::refreshUoaiConnection,
        onClearUoaiCredentials = viewModel::clearUoaiCredentials,
        onMemorySearchChange = viewModel::setMemorySearchQuery,
        onMemoryCategoryChange = viewModel::setMemoryCategoryFilter,
        onMemoryVaultFilterChange = viewModel::setMemoryVaultFilter,
        onEntityFilterChange = viewModel::setEntityFilter,
        onExtractPendingMemories = viewModel::extractPendingMemories,
        onRetryFailedMemoryExtraction = viewModel::retryFailedMemoryExtraction,
        onTogglePinMemory = viewModel::togglePinMemory,
        onEditMemory = viewModel::editMemory,
        onArchiveMemory = viewModel::archiveMemory,
        onRestoreMemory = viewModel::restoreArchivedMemory,
        onDeleteMemoryPermanently = viewModel::deleteMemoryPermanently,
        onPromoteMemoryToTask = viewModel::promoteMemoryToTask,
        onCaptureManualMemory = viewModel::captureManualMemory,
        onGenerateSummary = viewModel::generateSummary,
        onGenerateProductivityBrief = viewModel::generateProductivityBrief,
        onUpdateTaskStatus = viewModel::updateTaskStatus,
        onUpdateTaskPriority = viewModel::updateTaskPriority,
        onCreateManualTask = viewModel::createManualTask,
        onEditTask = viewModel::editTask,
        onHighlightTranscript = viewModel::highlightTranscript,
        onSetAsrProvider = viewModel::setAsrProvider,
        onSetAsrLanguage = viewModel::setAsrLanguage,
        onSetUoaiAsrProviderId = viewModel::setUoaiAsrProviderId,
        onSetUoaiAsrScript = viewModel::setUoaiAsrScript,
        onLoadUoaiAudioModel = viewModel::loadUoaiAudioModel,
        onUnloadUoaiAudioModels = viewModel::unloadUoaiAudioModels,
        onSetLlmProvider = viewModel::setLlmProvider,
        onSetAutoExtractMemory = viewModel::setAutoExtractMemory,
        onSetLocalWhisperUrl = viewModel::setLocalWhisperUrl,
        onSetKeepBackgroundLinkAlive = viewModel::setKeepBackgroundLinkAlive,
        onSetAutoSync = viewModel::setAutoSyncOnReconnect,
        onSetAutoTranscribe = viewModel::setAutoTranscribeSynced,
        onWipeAllLocalData = viewModel::wipeAllLocalSyncedData,
        onSwitchToSecondary = {
            viewModel.prepareForRoleSwitch()
            onSwitchToSecondary()
        }
    )
}

@Composable
private fun SecondaryModeHost(
    onStartActivity: (Intent) -> Unit,
    onSwitchToPrimary: () -> Unit,
    onOpenRolePicker: () -> Unit,
    viewModel: RecorderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissionsAndHealth()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionsAndHealth()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!uiState.settings.onboardingCompleted) {
        OnboardingFlowScreen(
            uiState = uiState,
            onRequestPermissions = {
                permissionLauncher.launch(viewModel.requiredPermissions())
            },
            onOpenBatterySettings = {
                runCatching { onStartActivity(viewModel.createBatteryOptimizationIntent()) }
                    .onFailure { onStartActivity(viewModel.createAppDetailsIntent()) }
            },
            onOpenAppSettings = {
                onStartActivity(viewModel.createAppDetailsIntent())
            },
            onInitializeIdentity = viewModel::initializeStorageAndIdentity,
            onStartBleHost = viewModel::startBleHost,
            onStopBleHost = viewModel::stopBleHost,
            onTogglePairingWindow = viewModel::togglePairingWindow,
            onApprovePairing = viewModel::approvePendingPairing,
            onRejectPairing = viewModel::rejectPendingPairing,
            onToggleMicTest = {
                if (uiState.micTestRunning) viewModel.stopMicTest() else viewModel.startMicTest()
            },
            onStopMicTest = viewModel::stopMicTest,
            onCompleteAndArm = {
                viewModel.completeOnboarding()
                viewModel.armAndStartRecording()
            },
            onCompleteOnly = viewModel::completeOnboarding,
            onSwitchRole = {
                viewModel.prepareForRoleSwitch()
                onOpenRolePicker()
            }
        )
    } else {
        RecorderOperationalShell(
            uiState = uiState,
            liveMeterFlow = viewModel.liveMeter,
            onArmAndStart = viewModel::armAndStartRecording,
            onEmergencyStop = viewModel::emergencyStop,
            onAcknowledgeInterruption = viewModel::acknowledgeInterruption,
            onVerifySegment = viewModel::verifySegment,
            onClearIntegrityMsg = viewModel::clearIntegrityMessage,
            onRequestPermissions = {
                permissionLauncher.launch(viewModel.requiredPermissions())
            },
            onOpenBatterySettings = {
                runCatching { onStartActivity(viewModel.createBatteryOptimizationIntent()) }
                    .onFailure { onStartActivity(viewModel.createAppDetailsIntent()) }
            },
            onOpenAppSettings = {
                onStartActivity(viewModel.createAppDetailsIntent())
            },
            onUpdateConfig = viewModel::updateConfig,
            onSetAudioSource = viewModel::setAudioSource,
            onSetPrivacyKillSwitch = viewModel::setPrivacyKillSwitch,
            onSetAutoDeleteSynced = viewModel::setAutoDeleteSynced,
            onSetLocalOnlyPauseSync = viewModel::setLocalOnlyPauseSync,
            onStartBleHost = viewModel::startBleHost,
            onStopBleHost = viewModel::stopBleHost,
            onTogglePairingWindow = viewModel::togglePairingWindow,
            onApprovePairing = viewModel::approvePendingPairing,
            onRejectPairing = viewModel::rejectPendingPairing,
            onUnpair = viewModel::unpairDevice,
            onWipeAll = viewModel::wipeAllRecordings,
            onReopenOnboarding = viewModel::reopenOnboarding,
            onToggleMicTest = {
                if (uiState.micTestRunning) viewModel.stopMicTest() else viewModel.startMicTest()
            },
            onSwitchToPrimary = {
                viewModel.prepareForRoleSwitch()
                onSwitchToPrimary()
            }
        )
    }
}
