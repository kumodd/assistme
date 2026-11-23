package com.personalai.primary

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
import com.personalai.primary.ui.PrimaryViewModel
import com.personalai.primary.ui.screens.PrimaryAppShell
import com.personalai.primary.ui.theme.PrimaryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: PrimaryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
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
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            PrimaryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PrimaryAppShell(
                        uiState = uiState,
                        onRequestPermissions = {
                            permissionLauncher.launch(viewModel.requiredPermissions())
                        },
                        onOpenAppSettings = {
                            startActivity(viewModel.appDetailsIntent())
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
                            runCatching { startActivity(viewModel.buildShareExportedIntent(exported)) }
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
                            launchIntent?.let { runCatching { startActivity(it) } }
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
                        onHighlightTranscript = viewModel::highlightTranscript,
                        onSetAsrProvider = viewModel::setAsrProvider,
                        onSetAsrLanguage = viewModel::setAsrLanguage,
                        onSetLlmProvider = viewModel::setLlmProvider,
                        onSetAutoExtractMemory = viewModel::setAutoExtractMemory,
                        onSetLocalWhisperUrl = viewModel::setLocalWhisperUrl,
                        onSetAutoSync = viewModel::setAutoSyncOnReconnect,
                        onSetAutoTranscribe = viewModel::setAutoTranscribeSynced,
                        onWipeAllLocalData = viewModel::wipeAllLocalSyncedData
                    )
                }
            }
        }
    }
}
