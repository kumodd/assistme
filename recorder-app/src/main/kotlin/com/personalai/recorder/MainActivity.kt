package com.personalai.recorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalai.recorder.ui.RecorderViewModel
import com.personalai.recorder.ui.screens.OnboardingFlowScreen
import com.personalai.recorder.ui.screens.RecorderOperationalShell
import com.personalai.recorder.ui.theme.RecorderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
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

            RecorderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!uiState.settings.onboardingCompleted) {
                        OnboardingFlowScreen(
                            uiState = uiState,
                            onRequestPermissions = {
                                permissionLauncher.launch(viewModel.requiredPermissions())
                            },
                            onOpenBatterySettings = {
                                runCatching { startActivity(viewModel.createBatteryOptimizationIntent()) }
                                    .onFailure { startActivity(viewModel.createAppDetailsIntent()) }
                            },
                            onOpenAppSettings = {
                                startActivity(viewModel.createAppDetailsIntent())
                            },
                            onInitializeIdentity = viewModel::initializeStorageAndIdentity,
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
                            onCompleteOnly = viewModel::completeOnboarding
                        )
                    } else {
                        RecorderOperationalShell(
                            uiState = uiState,
                            onArmAndStart = viewModel::armAndStartRecording,
                            onEmergencyStop = viewModel::emergencyStop,
                            onAcknowledgeInterruption = viewModel::acknowledgeInterruption,
                            onVerifySegment = viewModel::verifySegment,
                            onClearIntegrityMsg = viewModel::clearIntegrityMessage,
                            onRequestPermissions = {
                                permissionLauncher.launch(viewModel.requiredPermissions())
                            },
                            onOpenBatterySettings = {
                                runCatching { startActivity(viewModel.createBatteryOptimizationIntent()) }
                                    .onFailure { startActivity(viewModel.createAppDetailsIntent()) }
                            },
                            onOpenAppSettings = {
                                startActivity(viewModel.createAppDetailsIntent())
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
                            }
                        )
                    }
                }
            }
        }
    }
}
