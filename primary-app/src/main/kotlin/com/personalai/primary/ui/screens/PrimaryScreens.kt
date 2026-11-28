package com.personalai.primary.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalai.primary.asr.AsrLanguageMode
import com.personalai.primary.asr.AsrProviderType
import com.personalai.primary.asr.UoaiAsrScript
import com.personalai.primary.audio.AudioRetentionPolicy
import com.personalai.primary.audio.ExportedAudioResult
import com.personalai.primary.data.db.AsrProcessingStatus
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.SummaryType
import com.personalai.primary.data.db.SyncedSegmentEntity
import com.personalai.primary.data.db.TaskReminderEntity
import com.personalai.primary.data.db.TaskReminderStatus
import com.personalai.primary.memory.LlmProviderType
import com.personalai.primary.memory.uoai.UoaiConnectionState
import com.personalai.primary.memory.uoai.UoaiConstants
import com.personalai.primary.ui.MemoryVaultFilter
import com.personalai.primary.ui.PrimaryUiState
import com.personalai.primary.ui.theme.SecondMindColors
import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.RecorderConfigPayload
import com.personalai.protocol.RecorderState
import com.personalai.protocol.StopMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrimaryAppShell(
    uiState: PrimaryUiState,
    playbackProgressFlow: StateFlow<Float> = remember { MutableStateFlow(0f) },
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onBeginPairing: (String) -> Unit,
    onConfirmSas: () -> Unit,
    onCancelSas: () -> Unit,
    onConnectPaired: () -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: (StopMode) -> Unit,
    onRefreshAll: () -> Unit,
    onPing: () -> Unit,
    onSyncNow: () -> Unit,
    onSetConfig: (RecorderConfigPayload) -> Unit,
    onSetLocalOnlyPauseSync: (Boolean) -> Unit,
    onClearFeedback: () -> Unit,
    onClearError: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onLanguageFilterChange: (String) -> Unit,
    onToggleAudioPlayback: (SyncedSegmentEntity) -> Unit,
    onRetrySegmentAsr: (String) -> Unit,
    onRetryAllFailedAsr: () -> Unit,
    onRunPendingAsrNow: () -> Unit,
    onRefreshOfflineSpeechPacks: () -> Unit,
    onDownloadOfflineSpeechPacks: () -> Unit,
    onDownloadSpecificChunk: (SyncedSegmentEntity) -> Unit,
    onShareExportedAudio: (ExportedAudioResult) -> Unit,
    onDeleteSpecificChunkAudio: (SyncedSegmentEntity) -> Unit,
    onSetAudioRetentionPolicy: (AudioRetentionPolicy) -> Unit,
    onPurgeExpiredAudioNow: () -> Unit,
    onClearStorageNotice: () -> Unit,
    onClearExportFeedback: () -> Unit,
    onAskQuestion: (String) -> Unit,
    onStopAskAi: () -> Unit,
    onMemorySearchChange: (String) -> Unit,
    onMemoryCategoryChange: (MemoryCategory?) -> Unit,
    onMemoryVaultFilterChange: (MemoryVaultFilter) -> Unit,
    onEntityFilterChange: (String?) -> Unit,
    onExtractPendingMemories: () -> Unit,
    onRetryFailedMemoryExtraction: () -> Unit,
    onTogglePinMemory: (String, Boolean) -> Unit,
    onEditMemory: (String, String, String, MemoryCategory) -> Unit,
    onArchiveMemory: (String) -> Unit,
    onRestoreMemory: (String) -> Unit,
    onDeleteMemoryPermanently: (String) -> Unit,
    onPromoteMemoryToTask: (MemoryItemEntity) -> Unit,
    onCaptureManualMemory: (String, String, MemoryCategory, Boolean, Boolean) -> Unit,
    onGenerateSummary: (SummaryType) -> Unit,
    onGenerateProductivityBrief: () -> Unit,
    onUpdateTaskStatus: (String, TaskReminderStatus) -> Unit,
    onUpdateTaskPriority: (String, Int) -> Unit,
    onCreateManualTask: (String, String, String?, Int) -> Unit,
    onEditTask: (TaskReminderEntity, String, String, String?, Int) -> Unit,
    onHighlightTranscript: (String?) -> Unit,
    onSetAsrProvider: (AsrProviderType) -> Unit,
    onSetAsrLanguage: (AsrLanguageMode) -> Unit,
    onSetUoaiAsrProviderId: (String) -> Unit,
    onSetUoaiAsrScript: (UoaiAsrScript) -> Unit,
    onLoadUoaiAudioModel: (String) -> Unit,
    onUnloadUoaiAudioModels: () -> Unit,
    onSetLlmProvider: (LlmProviderType) -> Unit,
    onLaunchUoaiRegistration: (Intent) -> Unit,
    onOpenUoaiProvider: (Intent?) -> Unit,
    onRefreshUoaiConnection: () -> Unit,
    onClearUoaiCredentials: () -> Unit,
    onSetAutoExtractMemory: (Boolean) -> Unit,
    onSetLocalWhisperUrl: (String) -> Unit,
    onSetKeepBackgroundLinkAlive: (Boolean) -> Unit,
    onSetAutoSync: (Boolean) -> Unit,
    onSetAutoTranscribe: (Boolean) -> Unit,
    onWipeAllLocalData: () -> Unit,
    onSwitchToSecondary: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var deviceSubTab by rememberSaveable { mutableIntStateOf(2) } // 0=Recorder, 1=Pairing, 2=AI & Storage
    var memoryHubSubTab by rememberSaveable { mutableIntStateOf(0) } // 0=Vault, 1=Insights, 2=Digests, 3=Tasks
    var showQuickCaptureOnHome by remember { mutableStateOf(false) }

    val askListState = rememberLazyListState()
    val vaultListState = rememberLazyListState()
    val audioListState = rememberLazyListState()

    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    LaunchedEffect(uiState.storageNotice, uiState.lastExportedAudio) {
        val notice = uiState.storageNotice ?: return@LaunchedEffect
        val exported = uiState.lastExportedAudio
        val result = snackbarHostState.showSnackbar(
            message = notice,
            actionLabel = if (exported != null) "Share" else null,
            withDismissAction = true,
            duration = if (exported != null) SnackbarDuration.Long else SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed && exported != null) {
            onShareExportedAudio(exported)
        }
        onClearStorageNotice()
        onClearExportFeedback()
    }

    val showUndoSnackbar: (String, () -> Unit) -> Unit = { message, onUndo ->
        uiScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val res = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (res == SnackbarResult.ActionPerformed) {
                onUndo()
            }
        }
    }

    val openTranscriptSource: (String) -> Unit = { transcriptId ->
        onHighlightTranscript(transcriptId)
        selectedTab = 3
    }

    val navItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = SecondMindColors.HeroCoral,
        selectedTextColor = SecondMindColors.PrimaryInk,
        indicatorColor = SecondMindColors.HeroCoralSoft,
        unselectedIconColor = SecondMindColors.MutedText,
        unselectedTextColor = SecondMindColors.MutedText
    )

    Scaffold(
        containerColor = SecondMindColors.Canvas,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            when (selectedTab) {
                0 -> GalleryTopBar(
                    title = "Sunbo",
                    centered = false,
                    trailing = {
                        val uoaiConn = uiState.uoaiConnection
                        val isRule = uiState.appSettings.llmProviderType == LlmProviderType.LOCAL_HEURISTIC_OFFLINE
                        val ready = isRule || uoaiConn is UoaiConnectionState.Connected
                        StatusPill(
                            label = when {
                                isRule -> "Offline AI"
                                uoaiConn is UoaiConnectionState.Connected ->
                                    uoaiConn.activeModel?.name?.takeIf { it.isNotBlank() }?.take(12) ?: "Gemma 4"
                                else -> "Setup AI"
                            },
                            dotColor = if (ready) SecondMindColors.EmeraldPrivacy else SecondMindColors.AmberHighlight,
                            containerColor = if (ready) SecondMindColors.EmeraldContainer else SecondMindColors.AmberContainer,
                            onClick = { deviceSubTab = 2; selectedTab = 4 }
                        )
                    }
                )
                1 -> GalleryTopBar(
                    title = "Ask Sunbo",
                    centered = false,
                    trailing = {
                        val conn = uiState.uoaiConnection
                        if (uiState.appSettings.llmProviderType == LlmProviderType.LOCAL_HEURISTIC_OFFLINE) {
                            StatusPill(
                                label = "Rule Engine",
                                dotColor = SecondMindColors.CyanAccent,
                                containerColor = SecondMindColors.BlueSoftContainer
                            )
                        } else if (conn is UoaiConnectionState.Connected) {
                            StatusPill(
                                label = conn.activeModel?.name?.take(12) ?: "Gemma 4",
                                dotColor = SecondMindColors.EmeraldPrivacy,
                                containerColor = SecondMindColors.EmeraldContainer
                            )
                        }
                    }
                )
                2 -> GalleryTopBar(
                    title = "Your 2nd Mind",
                    centered = false,
                    trailing = {
                        SoftPillButton(
                            text = "Capture",
                            icon = Icons.Default.Add,
                            compact = true,
                            containerColor = SecondMindColors.PrimaryInk,
                            contentColor = Color.White,
                            onClick = { showQuickCaptureOnHome = true }
                        )
                    }
                )
                3 -> GalleryTopBar(title = "Audio & Transcripts")
                else -> GalleryTopBar(title = "Settings")
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(thickness = 0.75.dp, color = SecondMindColors.HairlineBorder)
                NavigationBar(
                    containerColor = SecondMindColors.TopBarSurface,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.Today, contentDescription = null) },
                        label = { Text("Today") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                        label = { Text("Ask") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.Psychology, contentDescription = null) },
                        label = { Text("2nd Mind") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                        label = { Text("Audio") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 4,
                        onClick = { selectedTab = 4 },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            uiState.lastFeedback?.let { fb ->
                if (!fb.success) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${formatHumanEnum(fb.commandType.name)}: ${fb.message}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            TextButton(onClick = onClearFeedback) { Text("OK", color = SecondMindColors.CoralAlert) }
                        }
                    }
                }
            }

            uiState.lastError?.let { err ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            err,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        IconButton(onClick = onClearError) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss error",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> TodayBriefingTab(
                    uiState = uiState,
                    onRequestPermissions = onRequestPermissions,
                    onConnectPaired = onConnectPaired,
                    onDisconnect = onDisconnect,
                    onRefreshAll = onRefreshAll,
                    onSyncNow = onSyncNow,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onSetLocalOnlyPauseSync = onSetLocalOnlyPauseSync,
                    onToggleTaskDone = { id, done ->
                        onUpdateTaskStatus(id, if (done) TaskReminderStatus.DONE else TaskReminderStatus.OPEN)
                        if (done) {
                            showUndoSnackbar("Task marked complete") {
                                onUpdateTaskStatus(id, TaskReminderStatus.OPEN)
                            }
                        }
                    },
                    onCaptureThoughtClick = { showQuickCaptureOnHome = true },
                    onGenerateDailyDigest = {
                        onGenerateSummary(SummaryType.DAILY)
                        memoryHubSubTab = 2
                        selectedTab = 2
                    },
                    onOpenPairing = { deviceSubTab = 1; selectedTab = 4 },
                    onOpenAskAi = { selectedTab = 1 },
                    onOpenVault = { memoryHubSubTab = 0; selectedTab = 2 },
                    onOpenInsights = { memoryHubSubTab = 1; selectedTab = 2 },
                    onOpenTasks = { memoryHubSubTab = 3; selectedTab = 2 },
                    onOpenAudio = { selectedTab = 3 },
                    onOpenAiSetup = { deviceSubTab = 2; selectedTab = 4 }
                )
                1 -> AskAiTab(
                    uiState = uiState,
                    listState = askListState,
                    onAskQuestion = onAskQuestion,
                    onStopAskAi = onStopAskAi,
                    onLaunchUoaiRegistration = onLaunchUoaiRegistration,
                    onOpenUoaiProvider = onOpenUoaiProvider,
                    onRefreshUoaiConnection = onRefreshUoaiConnection,
                    onClearUoaiCredentials = onClearUoaiCredentials,
                    onInspectSourceTranscript = openTranscriptSource
                )
                2 -> MemoryHubTab(
                    uiState = uiState,
                    selectedSubTab = memoryHubSubTab,
                    onSelectSubTab = { memoryHubSubTab = it },
                    vaultListState = vaultListState,
                    onMemorySearchChange = onMemorySearchChange,
                    onMemoryCategoryChange = onMemoryCategoryChange,
                    onMemoryVaultFilterChange = onMemoryVaultFilterChange,
                    onEntityFilterChange = onEntityFilterChange,
                    onExtractPending = onExtractPendingMemories,
                    onRetryFailedExtraction = onRetryFailedMemoryExtraction,
                    onTogglePinMemory = onTogglePinMemory,
                    onEditMemory = onEditMemory,
                    onArchiveMemory = onArchiveMemory,
                    onRestoreMemory = onRestoreMemory,
                    onDeleteMemoryPermanently = onDeleteMemoryPermanently,
                    onPromoteMemoryToTask = onPromoteMemoryToTask,
                    onCaptureManualMemory = onCaptureManualMemory,
                    onGenerateSummary = onGenerateSummary,
                    onGenerateProductivityBrief = onGenerateProductivityBrief,
                    onUpdateTaskStatus = onUpdateTaskStatus,
                    onUpdateTaskPriority = onUpdateTaskPriority,
                    onCreateManualTask = onCreateManualTask,
                    onEditTask = onEditTask,
                    onShowUndoSnackbar = showUndoSnackbar,
                    onInspectSourceTranscript = openTranscriptSource,
                    onOpenAudioStorage = { selectedTab = 3 }
                )
                3 -> AudioAndTranscriptsTab(
                    uiState = uiState,
                    listState = audioListState,
                    playbackProgressFlow = playbackProgressFlow,
                    onConnectPaired = onConnectPaired,
                    onSyncNow = onSyncNow,
                    onSearchQueryChange = onSearchQueryChange,
                    onLanguageFilterChange = onLanguageFilterChange,
                    onToggleAudioPlayback = onToggleAudioPlayback,
                    onDownloadSpecificChunk = onDownloadSpecificChunk,
                    onDeleteSpecificChunkAudio = onDeleteSpecificChunkAudio,
                    onSetAudioRetentionPolicy = onSetAudioRetentionPolicy,
                    onPurgeExpiredAudioNow = onPurgeExpiredAudioNow,
                    onRetrySegmentAsr = onRetrySegmentAsr,
                    onRetryAllFailedAsr = onRetryAllFailedAsr,
                    onRunPendingAsrNow = onRunPendingAsrNow,
                    onDownloadOfflineSpeechPacks = onDownloadOfflineSpeechPacks,
                    onLaunchUoaiRegistration = onLaunchUoaiRegistration,
                    onOpenUoaiProvider = onOpenUoaiProvider,
                    onClearHighlight = { onHighlightTranscript(null) },
                    onOpenSettings = { deviceSubTab = 2; selectedTab = 4 }
                )
                4 -> DeviceAndSettingsHubTab(
                    uiState = uiState,
                    selectedSubTab = deviceSubTab,
                    onSelectSubTab = { deviceSubTab = it },
                    onRequestPermissions = onRequestPermissions,
                    onOpenAppSettings = onOpenAppSettings,
                    onStartScan = onStartScan,
                    onStopScan = onStopScan,
                    onBeginPairing = onBeginPairing,
                    onConfirmSas = onConfirmSas,
                    onCancelSas = onCancelSas,
                    onUnpair = onUnpair,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onRefreshAll = onRefreshAll,
                    onPing = onPing,
                    onSyncNow = onSyncNow,
                    onSetConfig = onSetConfig,
                    onSetLocalOnlyPauseSync = onSetLocalOnlyPauseSync,
                    onSetAsrProvider = onSetAsrProvider,
                    onSetAsrLanguage = onSetAsrLanguage,
                    onSetUoaiAsrProviderId = onSetUoaiAsrProviderId,
                    onSetUoaiAsrScript = onSetUoaiAsrScript,
                    onLoadUoaiAudioModel = onLoadUoaiAudioModel,
                    onUnloadUoaiAudioModels = onUnloadUoaiAudioModels,
                    onRefreshOfflineSpeechPacks = onRefreshOfflineSpeechPacks,
                    onDownloadOfflineSpeechPacks = onDownloadOfflineSpeechPacks,
                    onSetLlmProvider = onSetLlmProvider,
                    onSetAudioRetentionPolicy = onSetAudioRetentionPolicy,
                    onPurgeExpiredAudioNow = onPurgeExpiredAudioNow,
                    onLaunchUoaiRegistration = onLaunchUoaiRegistration,
                    onOpenUoaiProvider = onOpenUoaiProvider,
                    onRefreshUoaiConnection = onRefreshUoaiConnection,
                    onClearUoaiCredentials = onClearUoaiCredentials,
                    onSetAutoExtractMemory = onSetAutoExtractMemory,
                    onSetLocalWhisperUrl = onSetLocalWhisperUrl,
                    onSetKeepBackgroundLinkAlive = onSetKeepBackgroundLinkAlive,
                    onSetAutoSync = onSetAutoSync,
                    onSetAutoTranscribe = onSetAutoTranscribe,
                    onWipeAllLocalData = onWipeAllLocalData,
                    onSwitchToSecondary = onSwitchToSecondary
                )
            }
        }
    }

    if (showQuickCaptureOnHome) {
        QuickCaptureThoughtDialog(
            onDismiss = { showQuickCaptureOnHome = false },
            onSave = { title, content, cat, pin, createTask ->
                showQuickCaptureOnHome = false
                onCaptureManualMemory(title, content, cat, pin, createTask)
            }
        )
    }
}

@Composable
private fun TodayBriefingTab(
    uiState: PrimaryUiState,
    onRequestPermissions: () -> Unit,
    onConnectPaired: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshAll: () -> Unit,
    onSyncNow: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: (StopMode) -> Unit,
    onSetLocalOnlyPauseSync: (Boolean) -> Unit,
    onToggleTaskDone: (String, Boolean) -> Unit,
    onCaptureThoughtClick: () -> Unit,
    onGenerateDailyDigest: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenAskAi: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenAiSetup: () -> Unit
) {
    val ins = uiState.insights
    val status = uiState.remoteStatus
    val isReady = uiState.bleState == BleConnectionState.READY
    val sync = uiState.syncProgress
    val asr = uiState.asrProgress
    val recState = status?.recorderState ?: RecorderState.UNCONFIGURED
    val openTasks = remember(uiState.tasks) { uiState.tasks.filter { it.status == TaskReminderStatus.OPEN }.take(3) }
    val pinnedMemories = remember(uiState.memories) { uiState.memories.filter { it.isPinned }.take(3) }

    val hasBlePerms = uiState.permissions.canUseBle
    val hasPaired = uiState.pairedDevice != null
    val hasAiReady = uiState.appSettings.llmProviderType == LlmProviderType.LOCAL_HEURISTIC_OFFLINE ||
        uiState.uoaiConnection is UoaiConnectionState.Connected
    val showQuickSetup = !hasBlePerms || !hasPaired || !hasAiReady

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(2.dp))

        // Airy editorial greeting block
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                ins.greeting,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = SecondMindColors.PrimaryInk
            )
            Text(
                "100% private offline memory • ${formatDurationFriendly(ins.todaySpokenSeconds)} captured today",
                style = MaterialTheme.typography.bodySmall,
                color = SecondMindColors.SecondaryInk
            )
        }

        // Compact Live Status Strip
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(
                label = "BLE: ${formatHumanEnum(uiState.bleState.name)}",
                dotColor = when (uiState.bleState) {
                    BleConnectionState.READY, BleConnectionState.SYNCHRONIZING -> SecondMindColors.CyanAccent
                    else -> SecondMindColors.MutedText
                },
                onClick = when {
                    uiState.pairedDevice == null -> onOpenPairing
                    uiState.bleState == BleConnectionState.DISCONNECTED -> onConnectPaired
                    else -> null
                }
            )
            StatusPill(
                label = "Mic: ${formatHumanEnum(recState.name)}",
                dotColor = if (recState.isArmedOrCapturing) SecondMindColors.EmeraldPrivacy else SecondMindColors.MutedText
            )
            val asrShort = when (uiState.appSettings.asrProviderType) {
                AsrProviderType.UOAI_PROVIDER -> "ASR: UOAI ${uiState.appSettings.uoaiAsrProviderId}"
                AsrProviderType.ANDROID_ON_DEVICE -> "ASR: Android"
                AsrProviderType.LOCAL_WHISPER_SERVER -> "ASR: Whisper"
            }
            StatusPill(
                label = asrShort,
                dotColor = SecondMindColors.HeroCoral,
                onClick = onOpenAiSetup
            )
        }

        // Guided First-Run Setup Checklist
        if (showQuickSetup) {
            MindSurfaceCard {
                StarEyebrowBadge(label = "Finish quick setup")
                Text(
                    "Quick Setup Checklist",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SecondMindColors.PrimaryInk
                )
                SetupChecklistRow(
                    done = hasBlePerms,
                    label = "Bluetooth permissions for companion sync",
                    actionLabel = "Grant",
                    onAction = onRequestPermissions
                )
                SetupChecklistRow(
                    done = hasPaired,
                    label = "Pair your Secondary Recorder phone",
                    actionLabel = "Pair",
                    onAction = onOpenPairing
                )
                SetupChecklistRow(
                    done = hasAiReady,
                    label = "Connect UOAI Provider for offline Gemma 4 AI & ASR",
                    actionLabel = "Setup",
                    onAction = onOpenAiSetup
                )
            }
        }

        // Today's 2nd Mind Pulse (4 floating pure-white KPI tiles)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                value = "${ins.todayClipsCount}",
                label = "Clips",
                accent = SecondMindColors.CyanAccent,
                modifier = Modifier.weight(1f),
                onClick = onOpenAudio
            )
            MetricTile(
                value = "${uiState.memories.size}",
                label = "Memories",
                accent = SecondMindColors.HeroCoral,
                modifier = Modifier.weight(1f),
                onClick = onOpenVault
            )
            MetricTile(
                value = "${ins.openTasksCount}",
                label = "Tasks",
                accent = SecondMindColors.AmberHighlight,
                modifier = Modifier.weight(1f),
                onClick = onOpenTasks
            )
            MetricTile(
                value = "${ins.captureStreakDays}d",
                label = "Streak",
                accent = SecondMindColors.EmeraldPrivacy,
                modifier = Modifier.weight(1f),
                onClick = onOpenInsights
            )
        }

        // Quick Productivity Pill Actions
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SoftPillButton(
                text = "Capture",
                icon = Icons.Default.Add,
                compact = true,
                containerColor = SecondMindColors.PrimaryInk,
                contentColor = Color.White,
                onClick = onCaptureThoughtClick
            )
            SoftPillButton(
                text = "Ask Sunbo",
                icon = Icons.Default.AutoAwesome,
                compact = true,
                containerColor = SecondMindColors.CardSurface,
                onClick = onOpenAskAi
            )
            SoftPillButton(
                text = "Daily Digest",
                compact = true,
                containerColor = SecondMindColors.CardSurface,
                onClick = onGenerateDailyDigest
            )
            SoftPillButton(
                text = "Insights",
                icon = Icons.Default.Insights,
                compact = true,
                containerColor = SecondMindColors.CardSurface,
                onClick = onOpenInsights
            )
        }

        // Live Pipeline Activity (only shown when active or error)
        if (sync.isSyncing || asr.isTranscribing || uiState.memoryStatus.isExtracting || !sync.lastError.isNullOrBlank()) {
            MindSurfaceCard(borderColor = SecondMindColors.CyanAccent.copy(alpha = 0.4f)) {
                Text(
                    "Live Processing Pipeline",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondMindColors.PrimaryInk
                )
                if (sync.isSyncing) {
                    Text(
                        "Syncing #${sync.activeSegmentSeq ?: "-"} via ${sync.transportLabel ?: "BLE"} (${sync.activeBytesReceived / 1024}/${sync.activeBytesTotal / 1024} KB)",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.SecondaryInk
                    )
                    val progress = if (sync.activeBytesTotal > 0) {
                        (sync.activeBytesReceived.toFloat() / sync.activeBytesTotal.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = SecondMindColors.CyanAccent,
                        trackColor = SecondMindColors.BlueSoftContainer
                    )
                }
                if (asr.isTranscribing) {
                    val asrEngineLabel = if (uiState.appSettings.asrProviderType == AsrProviderType.UOAI_PROVIDER) {
                        "via UOAI (${uiState.appSettings.uoaiAsrProviderId})"
                    } else {
                        "offline"
                    }
                    Text(
                        "Transcribing #${asr.activeSegmentSequence ?: "-"} $asrEngineLabel...",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.CyanAccent
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = SecondMindColors.CyanAccent,
                        trackColor = SecondMindColors.BlueSoftContainer
                    )
                }
                if (uiState.memoryStatus.isExtracting) {
                    Text(
                        "Distilling memories with Gemma offline...",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.VioletAi
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = SecondMindColors.VioletAi,
                        trackColor = SecondMindColors.VioletContainer
                    )
                }
                sync.lastError?.let {
                    Text("Sync Error: $it", color = SecondMindColors.CoralAlert, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Focus Tasks Spotlight
        GallerySectionLabel("Focus tasks")
        MindSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(SecondMindColors.AmberContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = SecondMindColors.AmberHighlight,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        "Upcoming Commitments",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SecondMindColors.PrimaryInk
                    )
                }
                TextButton(onClick = onOpenTasks) {
                    Text("All (${uiState.tasks.size})", color = SecondMindColors.CyanAccent)
                }
            }
            if (openTasks.isEmpty()) {
                Text(
                    "All clear! Spoken commitments & captured tasks appear here automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )
            } else {
                openTasks.forEach { task ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Checkbox(
                            checked = false,
                            onCheckedChange = { onToggleTaskDone(task.taskId, true) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                task.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SecondMindColors.PrimaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            task.dueLabel?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = SecondMindColors.AmberHighlight)
                            }
                        }
                    }
                }
            }
        }

        // Pinned Memories Spotlight
        if (pinnedMemories.isNotEmpty()) {
            GallerySectionLabel("Pinned highlights")
            MindSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StarEyebrowBadge(label = "Pinned in 2nd Mind")
                    TextButton(onClick = onOpenVault) {
                        Text("Open Vault", color = SecondMindColors.CyanAccent)
                    }
                }
                pinnedMemories.forEachIndexed { index, mem ->
                    if (index > 0) {
                        HorizontalDivider(thickness = 0.5.dp, color = SecondMindColors.HairlineBorder)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            mem.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SecondMindColors.PrimaryInk
                        )
                        Text(
                            mem.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.SecondaryInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Companion Recorder Card
        GallerySectionLabel("Companion recorder")
        MindSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SecondMindColors.BlueSoftContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = SecondMindColors.CyanAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            uiState.pairedDevice?.deviceName ?: "No Paired Secondary Recorder",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SecondMindColors.PrimaryInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Battery: ${status?.batteryPct?.takeIf { it >= 0 }?.let { "$it%" } ?: "--"} • Unsynced: ${status?.pendingSyncSegments ?: 0} • Audio: ${formatStorageBytes(uiState.activeAudioBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondMindColors.MutedText
                        )
                    }
                }
                if (isReady) {
                    IconButton(onClick = onRefreshAll) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh Secondary Recorder",
                            tint = SecondMindColors.SecondaryInk
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.pairedDevice == null) {
                    SoftPillButton(
                        text = "Pair Secondary Recorder",
                        compact = true,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = onOpenPairing
                    )
                } else if (uiState.bleState == BleConnectionState.DISCONNECTED) {
                    SoftPillButton(
                        text = "Connect",
                        icon = Icons.Default.Bluetooth,
                        compact = true,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = onConnectPaired
                    )
                } else {
                    if (isReady) {
                        SoftPillButton(
                            text = "Sync",
                            icon = Icons.Default.Sync,
                            compact = true,
                            enabled = !sync.isSyncing,
                            containerColor = SecondMindColors.PrimaryInk,
                            contentColor = Color.White,
                            onClick = onSyncNow
                        )
                        SoftPillButton(
                            text = "Resume",
                            icon = Icons.Default.Mic,
                            compact = true,
                            onClick = onStartRecording
                        )
                        SoftPillButton(
                            text = "Stop Mic",
                            compact = true,
                            containerColor = SecondMindColors.HeroCoralSoft,
                            contentColor = SecondMindColors.CoralAlert,
                            onClick = { onStopRecording(StopMode.STOP_SERVICE) }
                        )
                    }
                    TextButton(onClick = onDisconnect) {
                        Text("Disconnect", color = SecondMindColors.MutedText)
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = SecondMindColors.HairlineBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Pause Sync Only (Keep Recording)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )
                Switch(
                    checked = uiState.remoteConfig.localOnlyPauseSync,
                    onCheckedChange = onSetLocalOnlyPauseSync,
                    enabled = isReady
                )
            }
        }

        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun SetupChecklistRow(
    done: Boolean,
    label: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = if (done) "Completed" else "Pending",
            tint = if (done) SecondMindColors.EmeraldPrivacy else SecondMindColors.MutedText.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (done) SecondMindColors.MutedText else SecondMindColors.PrimaryInk
        )
        if (!done) {
            SoftPillButton(
                text = actionLabel,
                compact = true,
                onClick = onAction
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioAndTranscriptsTab(
    uiState: PrimaryUiState,
    listState: LazyListState = rememberLazyListState(),
    playbackProgressFlow: StateFlow<Float>,
    onConnectPaired: () -> Unit,
    onSyncNow: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onLanguageFilterChange: (String) -> Unit,
    onToggleAudioPlayback: (SyncedSegmentEntity) -> Unit,
    onDownloadSpecificChunk: (SyncedSegmentEntity) -> Unit,
    onDeleteSpecificChunkAudio: (SyncedSegmentEntity) -> Unit,
    onSetAudioRetentionPolicy: (AudioRetentionPolicy) -> Unit,
    onPurgeExpiredAudioNow: () -> Unit,
    onRetrySegmentAsr: (String) -> Unit,
    onRetryAllFailedAsr: () -> Unit,
    onRunPendingAsrNow: () -> Unit,
    onDownloadOfflineSpeechPacks: () -> Unit,
    onLaunchUoaiRegistration: (Intent) -> Unit,
    onOpenUoaiProvider: (Intent?) -> Unit,
    onClearHighlight: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val focusManager = LocalFocusManager.current
    val speechPacks = uiState.offlineSpeechPacks
    var pendingDeleteAudioSegment by remember { mutableStateOf<SyncedSegmentEntity?>(null) }
    val chipColors = galleryFilterChipColors(onWhiteCard = false)

    val hasPackError = remember(uiState.segmentsWithTranscripts) {
        uiState.segmentsWithTranscripts.any { item ->
            val err = item.segment.asrError.orEmpty()
            item.segment.asrStatus == AsrProcessingStatus.FAILED &&
                (err.contains("pack", ignoreCase = true) || err.contains("language", ignoreCase = true))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Play clips, save WAVs to Downloads/Sunbo, or auto-prune verified audio while keeping transcripts forever.",
                style = MaterialTheme.typography.bodySmall,
                color = SecondMindColors.SecondaryInk
            )
        }

        // Audio Chunk Auto-Deletion Card
        item {
            AudioRetentionBarCard(
                policy = uiState.appSettings.audioRetentionPolicy,
                activeAudioChunkCount = uiState.activeAudioChunkCount,
                activeAudioBytes = uiState.activeAudioBytes,
                onSelectPolicy = onSetAudioRetentionPolicy,
                onPurgeExpiredNow = onPurgeExpiredAudioNow,
                compact = true
            )
        }

        item {
            uiState.highlightedTranscriptId?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Highlighting cited source transcript",
                        color = SecondMindColors.CyanAccent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(onClick = onClearHighlight) { Text("Clear", color = SecondMindColors.CyanAccent) }
                }
            }
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                colors = galleryTextFieldColors(),
                placeholder = { Text("Search transcripts or chunk #...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val failedLabel = if (uiState.failedAsrCount > 0) "Failed (${uiState.failedAsrCount})" else "Failed"
                listOf(
                    "ALL" to "All",
                    "AUDIO_ONLY" to "Has Audio",
                    "FAILED" to failedLabel,
                    "PENDING" to "Pending",
                    "EN" to "English",
                    "HI" to "Hindi"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = uiState.languageFilter == key,
                        onClick = { onLanguageFilterChange(key) },
                        colors = chipColors,
                        border = null,
                        shape = RoundedCornerShape(50),
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (key == "FAILED" && uiState.failedAsrCount > 0 && uiState.languageFilter != key) {
                                    SecondMindColors.CoralAlert
                                } else {
                                    Color.Unspecified
                                },
                                maxLines = 1
                            )
                        }
                    )
                }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SoftPillButton(
                    text = "Sync",
                    icon = Icons.Default.Sync,
                    compact = true,
                    enabled = uiState.bleState == BleConnectionState.READY && !uiState.syncProgress.isSyncing,
                    containerColor = SecondMindColors.PrimaryInk,
                    contentColor = Color.White,
                    onClick = onSyncNow
                )
                if (uiState.pairedDevice != null && uiState.bleState == BleConnectionState.DISCONNECTED) {
                    SoftPillButton(
                        text = "Reconnect",
                        icon = Icons.Default.Bluetooth,
                        compact = true,
                        containerColor = SecondMindColors.CardSurface,
                        onClick = onConnectPaired
                    )
                }
                SoftPillButton(
                    text = "Transcribe",
                    compact = true,
                    containerColor = SecondMindColors.CardSurface,
                    onClick = onRunPendingAsrNow
                )
                if (uiState.failedAsrCount > 0) {
                    SoftPillButton(
                        text = "Retry Failed (${uiState.failedAsrCount})",
                        compact = true,
                        containerColor = SecondMindColors.HeroCoralSoft,
                        contentColor = SecondMindColors.CoralAlert,
                        onClick = onRetryAllFailedAsr
                    )
                } else {
                    SoftPillButton(
                        text = "Retry Failed",
                        compact = true,
                        containerColor = SecondMindColors.CardElevated,
                        onClick = onRetryAllFailedAsr
                    )
                }
                if (uiState.appSettings.asrProviderType == AsrProviderType.UOAI_PROVIDER) {
                    when (val conn = uiState.uoaiConnection) {
                        is UoaiConnectionState.NeedsRegistration -> {
                            SoftPillButton(
                                text = "Approve UOAI",
                                compact = true,
                                containerColor = SecondMindColors.BlueSoftContainer,
                                contentColor = SecondMindColors.CyanAccent,
                                onClick = { onLaunchUoaiRegistration(conn.registrationIntent) }
                            )
                        }
                        is UoaiConnectionState.ProviderNotRunning -> {
                            SoftPillButton(
                                text = "Open UOAI",
                                compact = true,
                                containerColor = SecondMindColors.BlueSoftContainer,
                                contentColor = SecondMindColors.CyanAccent,
                                onClick = { onOpenUoaiProvider(conn.launchProviderIntent) }
                            )
                        }
                        is UoaiConnectionState.ModelNotReady -> {
                            SoftPillButton(
                                text = "Load UOAI Model",
                                compact = true,
                                containerColor = SecondMindColors.BlueSoftContainer,
                                contentColor = SecondMindColors.CyanAccent,
                                onClick = { onOpenUoaiProvider(conn.launchProviderIntent) }
                            )
                        }
                        is UoaiConnectionState.Error -> {
                            if (conn.launchProviderIntent != null) {
                                SoftPillButton(
                                    text = "Open UOAI",
                                    compact = true,
                                    containerColor = SecondMindColors.BlueSoftContainer,
                                    contentColor = SecondMindColors.CyanAccent,
                                    onClick = { onOpenUoaiProvider(conn.launchProviderIntent) }
                                )
                            }
                        }
                        else -> Unit
                    }
                }
                if (uiState.appSettings.asrProviderType == AsrProviderType.ANDROID_ON_DEVICE &&
                    (hasPackError || !speechPacks.hasAnyInstalledPack)
                ) {
                    SoftPillButton(
                        text = if (speechPacks.isDownloading) "Downloading..." else "Speech Pack",
                        icon = Icons.Default.Download,
                        compact = true,
                        enabled = !speechPacks.isDownloading,
                        containerColor = SecondMindColors.BlueSoftContainer,
                        contentColor = SecondMindColors.CyanAccent,
                        onClick = onDownloadOfflineSpeechPacks
                    )
                }
            }
            if (speechPacks.isDownloading) {
                Text(
                    text = speechPacks.lastMessage ?: "Downloading offline speech pack (${speechPacks.activeDownloadLanguage ?: "EN"})...",
                    style = MaterialTheme.typography.labelSmall,
                    color = SecondMindColors.CyanAccent,
                    modifier = Modifier.padding(top = 6.dp)
                )
                val dlPct = speechPacks.downloadProgressPercent
                if (dlPct != null && dlPct > 0) {
                    LinearProgressIndicator(
                        progress = { (dlPct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = SecondMindColors.CyanAccent,
                        trackColor = SecondMindColors.BlueSoftContainer
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = SecondMindColors.CyanAccent,
                        trackColor = SecondMindColors.BlueSoftContainer
                    )
                }
            }
            if (uiState.asrProgress.isTranscribing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = SecondMindColors.HeroCoral,
                    trackColor = SecondMindColors.HeroCoralSoft
                )
            }
        }

        if (uiState.segmentsWithTranscripts.isEmpty()) {
            item {
                val hasFilters = uiState.searchQuery.isNotBlank() || uiState.languageFilter != "ALL"
                MindSurfaceCard {
                    if (uiState.totalSyncedCount > 0 && hasFilters) {
                        Text(
                            "No clips match your current filter.",
                            fontWeight = FontWeight.SemiBold,
                            color = SecondMindColors.PrimaryInk
                        )
                        Text(
                            "Try clearing the search query or switching back to All clips.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.SecondaryInk
                        )
                        SoftPillButton(
                            text = "Clear Filters",
                            compact = true,
                            onClick = {
                                onSearchQueryChange("")
                                onLanguageFilterChange("ALL")
                            }
                        )
                    } else {
                        Text(
                            "No synced audio chunks yet.",
                            fontWeight = FontWeight.SemiBold,
                            color = SecondMindColors.PrimaryInk
                        )
                        Text(
                            "1. Arm recording on your Secondary Recorder and speak.\n2. Keep Bluetooth connected on Primary for automatic sync.\n3. UOAI Provider (Gemma 4 / Whisper / Cactus) or Android Speech transcribes offline automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.SecondaryInk
                        )
                        SoftPillButton(
                            text = "Open AI & Storage Settings",
                            compact = true,
                            onClick = onOpenSettings
                        )
                    }
                }
            }
        } else {
            val ordered = if (uiState.highlightedTranscriptId != null) {
                uiState.segmentsWithTranscripts.sortedByDescending {
                    it.transcript?.transcriptId == uiState.highlightedTranscriptId
                }
            } else {
                uiState.segmentsWithTranscripts
            }
            items(ordered, key = { it.segment.segmentId }) { item ->
                val seg = item.segment
                val tr = item.transcript
                val isPlaying = uiState.playingSegmentId == seg.segmentId
                val isExporting = uiState.exportingSegmentId == seg.segmentId
                val isHighlighted = tr != null && tr.transcriptId == uiState.highlightedTranscriptId

                MindSurfaceCard(
                    containerColor = if (isHighlighted) SecondMindColors.BlueSoftContainer else SecondMindColors.CardSurface,
                    borderColor = if (isHighlighted) SecondMindColors.CyanAccent else SecondMindColors.HairlineBorder
                ) {
                    // Two-tier header so chunk # and duration are never truncated by timestamp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "#${seg.sequenceNumber} • ${"%.1f".format(seg.durationMs / 1000.0)}s",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = SecondMindColors.CyanAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatter.format(Date(seg.startTimestampMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = SecondMindColors.MutedText,
                                maxLines = 1
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(
                                label = if (seg.audioDeleted) "Pruned" else formatStorageBytes(seg.wavSizeBytes),
                                dotColor = if (seg.audioDeleted) SecondMindColors.MutedText else SecondMindColors.EmeraldPrivacy,
                                containerColor = SecondMindColors.CardElevated
                            )
                            Text(
                                text = tr?.language?.uppercase() ?: seg.asrStatus.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (seg.asrStatus == AsrProcessingStatus.FAILED) SecondMindColors.CoralAlert else SecondMindColors.PrimaryInk,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    if (seg.asrStatus == AsrProcessingStatus.PROCESSING) {
                        val activeEngine = if (uiState.appSettings.asrProviderType == AsrProviderType.UOAI_PROVIDER) {
                            "via UOAI (${uiState.appSettings.uoaiAsrProviderId})"
                        } else {
                            "offline"
                        }
                        Text(
                            text = "Transcribing $activeEngine...",
                            color = SecondMindColors.CyanAccent,
                            style = MaterialTheme.typography.bodySmall
                        )
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = SecondMindColors.HeroCoral,
                            trackColor = SecondMindColors.HeroCoralSoft
                        )
                    } else if (tr != null) {
                        SelectionContainer {
                            Text(
                                tr.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SecondMindColors.PrimaryInk
                            )
                        }
                        Text(
                            text = "ASR: ${tr.asrProvider} (${(tr.confidence * 100).toInt()}%) • Memory: ${formatHumanEnum(tr.memoryExtractionStatus.name)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondMindColors.MutedText
                        )
                    } else {
                        Text(
                            text = when (seg.asrStatus) {
                                AsrProcessingStatus.PENDING -> "Waiting for offline transcription..."
                                AsrProcessingStatus.PROCESSING -> "Transcribing audio offline..."
                                AsrProcessingStatus.FAILED -> "ASR Failed (audio preserved): ${seg.asrError ?: "Unknown error"}"
                                AsrProcessingStatus.COMPLETED -> "No transcript text"
                            },
                            color = if (seg.asrStatus == AsrProcessingStatus.FAILED) SecondMindColors.CoralAlert else SecondMindColors.SecondaryInk,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (isPlaying) {
                        val livePlaybackProgress by playbackProgressFlow.collectAsStateWithLifecycle()
                        LinearProgressIndicator(
                            progress = { livePlaybackProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = SecondMindColors.HeroCoral,
                            trackColor = SecondMindColors.HeroCoralSoft
                        )
                    }

                    if (!seg.audioDeleted) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SoftPillButton(
                                text = if (isPlaying) "Stop" else "Play",
                                icon = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                compact = true,
                                containerColor = if (isPlaying) SecondMindColors.HeroCoralSoft else SecondMindColors.CardElevated,
                                contentColor = if (isPlaying) SecondMindColors.HeroCoral else SecondMindColors.PrimaryInk,
                                onClick = { onToggleAudioPlayback(seg) }
                            )
                            SoftPillButton(
                                text = if (isExporting) "Saving" else "Save",
                                icon = Icons.Default.Download,
                                compact = true,
                                enabled = !isExporting,
                                onClick = { onDownloadSpecificChunk(seg) }
                            )
                            TextButton(
                                onClick = { onRetrySegmentAsr(seg.segmentId) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    if (tr == null) "Transcribe" else "Re-ASR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SecondMindColors.CyanAccent,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { pendingDeleteAudioSegment = seg }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete audio chunk #${seg.sequenceNumber}",
                                    tint = SecondMindColors.CoralAlert,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            "Audio chunk pruned to save phone storage — transcript & extracted memories remain intact.",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondMindColors.MutedText
                        )
                    }
                }
            }

            if (uiState.totalSyncedCount > 300) {
                item {
                    Text(
                        text = "Showing latest 300 of ${uiState.totalSyncedCount} synced clips. Use search above to find older transcripts.",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.MutedText,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }

    pendingDeleteAudioSegment?.let { seg ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAudioSegment = null },
            containerColor = SecondMindColors.CardSurface,
            title = { Text("Delete audio for clip #${seg.sequenceNumber}?") },
            text = { Text("This permanently deletes the encrypted WAV file (${formatStorageBytes(seg.wavSizeBytes)}). Your transcript and extracted memories remain intact.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteAudioSegment = null
                    onDeleteSpecificChunkAudio(seg)
                }) { Text("Delete Audio", color = SecondMindColors.CoralAlert) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAudioSegment = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DeviceAndSettingsHubTab(
    uiState: PrimaryUiState,
    selectedSubTab: Int,
    onSelectSubTab: (Int) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onBeginPairing: (String) -> Unit,
    onConfirmSas: () -> Unit,
    onCancelSas: () -> Unit,
    onUnpair: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: (StopMode) -> Unit,
    onRefreshAll: () -> Unit,
    onPing: () -> Unit,
    onSyncNow: () -> Unit,
    onSetConfig: (RecorderConfigPayload) -> Unit,
    onSetLocalOnlyPauseSync: (Boolean) -> Unit,
    onSetAsrProvider: (AsrProviderType) -> Unit,
    onSetAsrLanguage: (AsrLanguageMode) -> Unit,
    onSetUoaiAsrProviderId: (String) -> Unit,
    onSetUoaiAsrScript: (UoaiAsrScript) -> Unit,
    onLoadUoaiAudioModel: (String) -> Unit,
    onUnloadUoaiAudioModels: () -> Unit,
    onRefreshOfflineSpeechPacks: () -> Unit,
    onDownloadOfflineSpeechPacks: () -> Unit,
    onSetLlmProvider: (LlmProviderType) -> Unit,
    onSetAudioRetentionPolicy: (AudioRetentionPolicy) -> Unit,
    onPurgeExpiredAudioNow: () -> Unit,
    onLaunchUoaiRegistration: (Intent) -> Unit,
    onOpenUoaiProvider: (Intent?) -> Unit,
    onRefreshUoaiConnection: () -> Unit,
    onClearUoaiCredentials: () -> Unit,
    onSetAutoExtractMemory: (Boolean) -> Unit,
    onSetLocalWhisperUrl: (String) -> Unit,
    onSetKeepBackgroundLinkAlive: (Boolean) -> Unit,
    onSetAutoSync: (Boolean) -> Unit,
    onSetAutoTranscribe: (Boolean) -> Unit,
    onWipeAllLocalData: () -> Unit,
    onSwitchToSecondary: () -> Unit
) {
    val subTabChipColors = galleryFilterChipColors(onWhiteCard = false, useCoralSelection = true)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedSubTab == 2,
                onClick = { onSelectSubTab(2) },
                colors = subTabChipColors,
                border = null,
                shape = RoundedCornerShape(50),
                label = { Text("AI & Storage") }
            )
            FilterChip(
                selected = selectedSubTab == 0,
                onClick = { onSelectSubTab(0) },
                colors = subTabChipColors,
                border = null,
                shape = RoundedCornerShape(50),
                label = { Text("Recorder") }
            )
            FilterChip(
                selected = selectedSubTab == 1,
                onClick = { onSelectSubTab(1) },
                colors = subTabChipColors,
                border = null,
                shape = RoundedCornerShape(50),
                label = { Text("Pairing") }
            )
        }

        when (selectedSubTab) {
            0 -> ControlsAndAuditTab(
                uiState = uiState,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onRefreshAll = onRefreshAll,
                onPing = onPing,
                onSyncNow = onSyncNow,
                onSetConfig = onSetConfig,
                onSetLocalOnlyPauseSync = onSetLocalOnlyPauseSync
            )
            1 -> PairingTab(
                uiState = uiState,
                onRequestPermissions = onRequestPermissions,
                onOpenAppSettings = onOpenAppSettings,
                onStartScan = onStartScan,
                onStopScan = onStopScan,
                onBeginPairing = onBeginPairing,
                onConfirmSas = onConfirmSas,
                onCancelSas = onCancelSas,
                onUnpair = onUnpair
            )
            else -> SettingsAndHealthTab(
                uiState = uiState,
                onRefreshAll = onRefreshAll,
                onSetAsrProvider = onSetAsrProvider,
                onSetAsrLanguage = onSetAsrLanguage,
                onSetUoaiAsrProviderId = onSetUoaiAsrProviderId,
                onSetUoaiAsrScript = onSetUoaiAsrScript,
                onLoadUoaiAudioModel = onLoadUoaiAudioModel,
                onUnloadUoaiAudioModels = onUnloadUoaiAudioModels,
                onRefreshOfflineSpeechPacks = onRefreshOfflineSpeechPacks,
                onDownloadOfflineSpeechPacks = onDownloadOfflineSpeechPacks,
                onSetLlmProvider = onSetLlmProvider,
                onSetAudioRetentionPolicy = onSetAudioRetentionPolicy,
                onPurgeExpiredAudioNow = onPurgeExpiredAudioNow,
                onLaunchUoaiRegistration = onLaunchUoaiRegistration,
                onOpenUoaiProvider = onOpenUoaiProvider,
                onRefreshUoaiConnection = onRefreshUoaiConnection,
                onClearUoaiCredentials = onClearUoaiCredentials,
                onSetAutoExtractMemory = onSetAutoExtractMemory,
                onSetLocalWhisperUrl = onSetLocalWhisperUrl,
                onSetKeepBackgroundLinkAlive = onSetKeepBackgroundLinkAlive,
                onSetAutoSync = onSetAutoSync,
                onSetAutoTranscribe = onSetAutoTranscribe,
                onWipeAllLocalData = onWipeAllLocalData,
                onSwitchToSecondary = onSwitchToSecondary
            )
        }
    }
}

@Composable
private fun PairingTab(
    uiState: PrimaryUiState,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onBeginPairing: (String) -> Unit,
    onConfirmSas: () -> Unit,
    onCancelSas: () -> Unit,
    onUnpair: () -> Unit
) {
    var confirmUnpair by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!uiState.permissions.canUseBle) {
            item {
                MindSurfaceCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    borderColor = SecondMindColors.CoralAlert.copy(alpha = 0.35f)
                ) {
                    Text(
                        "Bluetooth Permissions Required",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    uiState.permissions.items.filter { !it.granted }.forEach {
                        Text(
                            "• ${it.title}: ${it.rationale}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftPillButton(
                            text = "Grant Permissions",
                            compact = true,
                            containerColor = SecondMindColors.PrimaryInk,
                            contentColor = Color.White,
                            onClick = onRequestPermissions
                        )
                        SoftPillButton(
                            text = "Settings",
                            compact = true,
                            containerColor = SecondMindColors.CardSurface,
                            onClick = onOpenAppSettings
                        )
                    }
                }
            }
        }

        uiState.pairedDevice?.let { paired ->
            item {
                MindSurfaceCard {
                    StarEyebrowBadge(label = "Encrypted Bluetooth link paired")
                    Text(
                        paired.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SecondMindColors.PrimaryInk
                    )
                    Text(
                        "ID: ${paired.deviceId.take(12)}… • Last BLE Addr: ${paired.bleAddress}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.SecondaryInk
                    )
                    SoftPillButton(
                        text = "Unpair Secondary Recorder",
                        compact = true,
                        containerColor = SecondMindColors.HeroCoralSoft,
                        contentColor = SecondMindColors.CoralAlert,
                        onClick = { confirmUnpair = true }
                    )
                }
            }
        }

        uiState.pendingSas?.let { sas ->
            item {
                MindSurfaceCard(
                    containerColor = SecondMindColors.BlueSoftContainer,
                    borderColor = SecondMindColors.CyanAccent.copy(alpha = 0.45f)
                ) {
                    Text(
                        "Verify 6-Digit Pairing Code on Both Phones",
                        fontWeight = FontWeight.Bold,
                        color = SecondMindColors.PrimaryInk
                    )
                    Text(
                        "Recorder: ${sas.serverDeviceName} (${sas.serverDeviceId.take(8)}…)",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.SecondaryInk
                    )
                    Text(
                        sas.sasCode,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = SecondMindColors.CyanAccent
                    )
                    Text(
                        "Approve on your Secondary Recorder as well, then tap Confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.SecondaryInk
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftPillButton(
                            text = "Confirm SAS Match",
                            compact = true,
                            containerColor = SecondMindColors.PrimaryInk,
                            contentColor = Color.White,
                            onClick = onConfirmSas
                        )
                        SoftPillButton(
                            text = "Cancel",
                            compact = true,
                            containerColor = SecondMindColors.CardSurface,
                            onClick = onCancelSas
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!uiState.isScanning) {
                    SoftPillButton(
                        text = "Scan for Secondary Recorder",
                        icon = Icons.Default.Bluetooth,
                        enabled = uiState.permissions.canUseBle,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = onStartScan,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    SoftPillButton(
                        text = "Stop Scan",
                        containerColor = SecondMindColors.HeroCoralSoft,
                        contentColor = SecondMindColors.HeroCoral,
                        onClick = onStopScan,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (uiState.isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = SecondMindColors.HeroCoral,
                    trackColor = SecondMindColors.HeroCoralSoft
                )
            }
        }

        items(uiState.discoveredDevices, key = { it.address }) { dev ->
            MindSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dev.name, fontWeight = FontWeight.SemiBold, color = SecondMindColors.PrimaryInk)
                        Text(
                            "Addr: ${dev.address} • RSSI: ${dev.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.MutedText
                        )
                        dev.deviceTagHex?.let {
                            Text("App Tag: $it", style = MaterialTheme.typography.labelSmall, color = SecondMindColors.CyanAccent)
                        }
                    }
                    SoftPillButton(
                        text = "Pair",
                        compact = true,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = { onBeginPairing(dev.address) }
                    )
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            containerColor = SecondMindColors.CardSurface,
            title = { Text("Unpair Secondary Recorder?") },
            text = { Text("This clears the encrypted Bluetooth pairing keys on Primary. Your synced transcripts and memories stay safe, and you can pair again anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    onUnpair()
                }) { Text("Unpair", color = SecondMindColors.CoralAlert) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsAndAuditTab(
    uiState: PrimaryUiState,
    onStartRecording: () -> Unit,
    onStopRecording: (StopMode) -> Unit,
    onRefreshAll: () -> Unit,
    onPing: () -> Unit,
    onSyncNow: () -> Unit,
    onSetConfig: (RecorderConfigPayload) -> Unit,
    onSetLocalOnlyPauseSync: (Boolean) -> Unit
) {
    val isReady = uiState.bleState == BleConnectionState.READY
    val cfg = uiState.remoteConfig
    val batt = uiState.remoteBattery
    val storage = uiState.remoteStorage
    val info = uiState.remoteDeviceInfo
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MindSurfaceCard {
                Text(
                    "Remote Secondary Recorder Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondMindColors.PrimaryInk
                )
                Text(
                    "Android 12–17 requires the Secondary Recorder's microphone service to be armed visibly on that device first; remote Start resumes armed standby.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoftPillButton(
                        text = "Start / Resume",
                        icon = Icons.Default.Mic,
                        compact = true,
                        enabled = isReady,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = onStartRecording
                    )
                    SoftPillButton(
                        text = "Sync Now",
                        icon = Icons.Default.Sync,
                        compact = true,
                        enabled = isReady && !uiState.syncProgress.isSyncing,
                        containerColor = SecondMindColors.BlueSoftContainer,
                        contentColor = SecondMindColors.CyanAccent,
                        onClick = onSyncNow
                    )
                    SoftPillButton(
                        text = "Pause Standby",
                        compact = true,
                        enabled = isReady,
                        onClick = { onStopRecording(StopMode.STANDBY_PAUSE) }
                    )
                    SoftPillButton(
                        text = "Stop Mic",
                        compact = true,
                        enabled = isReady,
                        containerColor = SecondMindColors.HeroCoralSoft,
                        contentColor = SecondMindColors.CoralAlert,
                        onClick = { onStopRecording(StopMode.STOP_SERVICE) }
                    )
                    SoftPillButton(
                        text = "Ping",
                        compact = true,
                        enabled = isReady,
                        onClick = onPing
                    )
                    SoftPillButton(
                        text = "Refresh Telemetry",
                        icon = Icons.Default.Refresh,
                        compact = true,
                        enabled = isReady,
                        onClick = onRefreshAll
                    )
                }
            }
        }

        item {
            val batteryPct = batt?.batteryPct ?: uiState.remoteStatus?.batteryPct ?: -1
            val batteryLabel = if (batteryPct >= 0) "$batteryPct% (${if (batt?.isCharging == true) "Charging" else "On Battery"})" else "--"
            val freeMb = (storage?.freeBytes ?: 0L) / (1024L * 1024L)
            val totalMb = (storage?.totalBytes ?: 0L) / (1024L * 1024L)
            MindSurfaceCard {
                Text(
                    "Secondary Recorder Health & Specs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondMindColors.PrimaryInk
                )
                Text("Battery: $batteryLabel", style = MaterialTheme.typography.bodySmall, color = SecondMindColors.PrimaryInk)
                Text(
                    "Power Save: ${if (batt?.isPowerSaveMode == true) "On" else "Off"} • Thermal: ${formatThermalStatusLabel(batt?.thermalStatus ?: 0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )
                Text(
                    "Free Storage: ${if (totalMb > 0) "$freeMb / $totalMb MB" else "--"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )
                Text(
                    "Device: ${info?.let { "${it.manufacturer} ${it.model} (Android SDK ${it.androidSdk})" } ?: uiState.pairedDevice?.deviceName ?: "--"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.MutedText
                )
                Text(
                    "Last Synced Sequence: #${uiState.appSettings.lastSyncedSequence}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SecondMindColors.MutedText
                )
            }
        }

        item {
            MindSurfaceCard {
                Text(
                    "Remote VAD & Sync Config",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondMindColors.PrimaryInk
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Pause Sync & Transcripts Only",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.PrimaryInk
                    )
                    Switch(checked = cfg.localOnlyPauseSync, onCheckedChange = onSetLocalOnlyPauseSync, enabled = isReady)
                }
                var draftRemoteVad by remember(cfg.vadThreshold) { mutableFloatStateOf(cfg.vadThreshold) }
                Text("VAD Threshold: ${"%.2f".format(draftRemoteVad)}", style = MaterialTheme.typography.bodySmall, color = SecondMindColors.SecondaryInk)
                Slider(
                    value = draftRemoteVad,
                    onValueChange = { draftRemoteVad = it },
                    onValueChangeFinished = { onSetConfig(cfg.copy(vadThreshold = draftRemoteVad)) },
                    valueRange = 0.20f..0.85f,
                    enabled = isReady
                )
            }
        }

        item { GallerySectionLabel("Command audit log") }

        items(uiState.auditLog, key = { it.commandId }) { item ->
            MindSurfaceCard {
                Text(
                    text = "${formatter.format(Date(item.sentAtMs))} • ${item.commandType} • ${if (item.success) "OK" else item.errorCode} (${item.rttMs}ms)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.success) SecondMindColors.EmeraldPrivacy else SecondMindColors.CoralAlert
                )
                Text(item.message, style = MaterialTheme.typography.bodySmall, color = SecondMindColors.SecondaryInk)
            }
        }
    }
}

@Composable
private fun SettingsAndHealthTab(
    uiState: PrimaryUiState,
    onRefreshAll: () -> Unit,
    onSetAsrProvider: (AsrProviderType) -> Unit,
    onSetAsrLanguage: (AsrLanguageMode) -> Unit,
    onSetUoaiAsrProviderId: (String) -> Unit,
    onSetUoaiAsrScript: (UoaiAsrScript) -> Unit,
    onLoadUoaiAudioModel: (String) -> Unit,
    onUnloadUoaiAudioModels: () -> Unit,
    onRefreshOfflineSpeechPacks: () -> Unit,
    onDownloadOfflineSpeechPacks: () -> Unit,
    onSetLlmProvider: (LlmProviderType) -> Unit,
    onSetAudioRetentionPolicy: (AudioRetentionPolicy) -> Unit,
    onPurgeExpiredAudioNow: () -> Unit,
    onLaunchUoaiRegistration: (Intent) -> Unit,
    onOpenUoaiProvider: (Intent?) -> Unit,
    onRefreshUoaiConnection: () -> Unit,
    onClearUoaiCredentials: () -> Unit,
    onSetAutoExtractMemory: (Boolean) -> Unit,
    onSetLocalWhisperUrl: (String) -> Unit,
    onSetKeepBackgroundLinkAlive: (Boolean) -> Unit,
    onSetAutoSync: (Boolean) -> Unit,
    onSetAutoTranscribe: (Boolean) -> Unit,
    onWipeAllLocalData: () -> Unit,
    onSwitchToSecondary: () -> Unit
) {
    val settings = uiState.appSettings
    val cardChipColors = galleryFilterChipColors(onWhiteCard = true)

    var localWhisperInput by rememberSaveable(settings.localWhisperBaseUrl) { mutableStateOf(settings.localWhisperBaseUrl) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmSwitchRole by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        MindSurfaceCard(
            containerColor = SecondMindColors.EmeraldContainer,
            borderColor = SecondMindColors.EmeraldPrivacy.copy(alpha = 0.25f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = SecondMindColors.EmeraldPrivacy, modifier = Modifier.size(18.dp))
                Text(
                    "100% On-Device Privacy Architecture",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SecondMindColors.EmeraldPrivacy
                )
            }
            Text(
                "Zero cloud AI APIs and zero external API keys. Audio, transcripts, memories, and LLM prompts never leave this phone — inference runs locally via Universal Offline AI Provider (127.0.0.1:8765).",
                style = MaterialTheme.typography.bodySmall,
                color = SecondMindColors.PrimaryInk
            )
        }

        MindSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Device Role",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SecondMindColors.PrimaryInk
                )
                StatusPill(
                    label = "Primary Mode",
                    dotColor = SecondMindColors.HeroCoral,
                    containerColor = SecondMindColors.HeroCoralSoft
                )
            }
            Text(
                "Running as your Primary Sunbo 2nd Mind. You can switch this device to Secondary Recorder mode anytime without losing local data.",
                style = MaterialTheme.typography.bodySmall,
                color = SecondMindColors.SecondaryInk
            )
            SoftPillButton(
                text = "Switch to Secondary Recorder Mode",
                onClick = { confirmSwitchRole = true },
                modifier = Modifier.fillMaxWidth()
            )
        }

        AudioRetentionBarCard(
            policy = settings.audioRetentionPolicy,
            activeAudioChunkCount = uiState.activeAudioChunkCount,
            activeAudioBytes = uiState.activeAudioBytes,
            onSelectPolicy = onSetAudioRetentionPolicy,
            onPurgeExpiredNow = onPurgeExpiredAudioNow,
            compact = false
        )

        GallerySectionLabel("Offline AI & LLM Engine")
        MindSurfaceCard {
            LlmProviderType.entries.forEach { llm ->
                val subtitle = when (llm) {
                    LlmProviderType.UOAI_OFFLINE -> "On-device Gemma 4 via local loopback (127.0.0.1:8765)"
                    LlmProviderType.LOCAL_HEURISTIC_OFFLINE -> "Zero-dependency built-in extractor & search (no provider app needed)"
                }
                SelectableOptionCard(
                    title = llm.displayName,
                    subtitle = subtitle,
                    selected = settings.llmProviderType == llm,
                    onClick = { onSetLlmProvider(llm) }
                )
            }

            if (settings.llmProviderType == LlmProviderType.UOAI_OFFLINE) {
                UoaiConnectionStatusCard(
                    connection = uiState.uoaiConnection,
                    notice = uiState.uoaiNotice,
                    onLaunchRegistration = onLaunchUoaiRegistration,
                    onOpenProvider = onOpenUoaiProvider,
                    onRefresh = onRefreshUoaiConnection,
                    onClearCredentials = onClearUoaiCredentials,
                    compact = false
                )
            }

            HorizontalDivider(thickness = 0.5.dp, color = SecondMindColors.HairlineBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep Background Sync Link Alive", style = MaterialTheme.typography.bodyMedium, color = SecondMindColors.PrimaryInk)
                    Text(
                        "Runs a quiet notification so BLE auto-reconnect & live sync stay active when minimized or closed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.MutedText
                    )
                }
                Switch(checked = settings.keepBackgroundLinkAlive, onCheckedChange = onSetKeepBackgroundLinkAlive)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Auto-Sync New Audio (Live + Reconnect)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondMindColors.PrimaryInk
                )
                Switch(checked = settings.autoSyncOnReconnect, onCheckedChange = onSetAutoSync)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Auto-Transcribe Synced Audio",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondMindColors.PrimaryInk
                )
                Switch(checked = settings.autoTranscribeSynced, onCheckedChange = onSetAutoTranscribe)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Auto-Extract Personal Memories",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondMindColors.PrimaryInk
                )
                Switch(checked = settings.autoExtractMemory, onCheckedChange = onSetAutoExtractMemory)
            }
        }

        GallerySectionLabel("Offline Speech Recognition (ASR)")
        MindSurfaceCard {
            StarEyebrowBadge(label = "Preferred: Direct Gemma 4 Multimodal")
            AsrProviderType.entries.forEach { provider ->
                val subtitle = when (provider) {
                    AsrProviderType.UOAI_PROVIDER -> "Recommended • Direct Gemma 4 audio, Whisper, Cactus, or custom UOAI engines"
                    AsrProviderType.ANDROID_ON_DEVICE -> "Uses Android 13+ built-in offline speech packs"
                    AsrProviderType.LOCAL_WHISPER_SERVER -> "Connects to a local loopback Whisper HTTP server"
                }
                SelectableOptionCard(
                    title = provider.displayName,
                    subtitle = subtitle,
                    selected = settings.asrProviderType == provider,
                    onClick = { onSetAsrProvider(provider) }
                )
            }
            Text("Language Mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = SecondMindColors.PrimaryInk)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AsrLanguageMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.asrLanguageMode == mode,
                        onClick = { onSetAsrLanguage(mode) },
                        colors = cardChipColors,
                        border = null,
                        shape = RoundedCornerShape(50),
                        label = { Text(mode.displayName) }
                    )
                }
            }
            if (settings.asrProviderType == AsrProviderType.UOAI_PROVIDER) {
                val audioStatus = uiState.uoaiAudioStatus
                val discoveredProviders = uiState.uoaiAudioProviders
                val providerById = remember(discoveredProviders) {
                    discoveredProviders.associateBy { it.id.lowercase() }
                }
                val anyReady = audioStatus?.gemma4AudioReady == true || discoveredProviders.any { it.ready }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "UOAI Transcription Engine",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SecondMindColors.PrimaryInk
                    )
                    StatusPill(
                        label = when {
                            audioStatus?.gemma4AudioReady == true -> "Gemma 4 Ready"
                            anyReady -> "UOAI ASR Ready"
                            uiState.uoaiConnection is UoaiConnectionState.NeedsRegistration -> "Needs Approval"
                            uiState.uoaiConnection is UoaiConnectionState.ProviderNotRunning -> "Provider Stopped"
                            uiState.uoaiConnection is UoaiConnectionState.ProviderNotInstalled -> "Not Installed"
                            else -> "Check UOAI"
                        },
                        dotColor = if (anyReady) SecondMindColors.EmeraldPrivacy else SecondMindColors.AmberHighlight,
                        containerColor = if (anyReady) SecondMindColors.EmeraldContainer else SecondMindColors.AmberContainer
                    )
                }

                val engineOptions = remember(discoveredProviders) {
                    val builtins = listOf(
                        UoaiConstants.AUDIO_PROVIDER_GEMMA4 to "Gemma 4 (Preferred)",
                        UoaiConstants.AUDIO_PROVIDER_AUTO to "Auto",
                        UoaiConstants.AUDIO_PROVIDER_WHISPER to "Whisper",
                        UoaiConstants.AUDIO_PROVIDER_CACTUS to "Cactus Hinglish"
                    )
                    val extras = discoveredProviders
                        .filter { !it.builtin && it.enabled && it.id.isNotBlank() }
                        .map { it.id to it.name.ifBlank { it.id } }
                    builtins + extras
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    engineOptions.forEach { (id, label) ->
                        val liveInfo = providerById[id.lowercase()]
                        val readySuffix = when {
                            liveInfo?.ready == true -> " • Ready"
                            id == UoaiConstants.AUDIO_PROVIDER_GEMMA4 && audioStatus?.gemma4AudioReady == true -> " • Ready"
                            else -> ""
                        }
                        FilterChip(
                            selected = settings.uoaiAsrProviderId.equals(id, ignoreCase = true),
                            onClick = { onSetUoaiAsrProviderId(id) },
                            colors = cardChipColors,
                            border = null,
                            shape = RoundedCornerShape(50),
                            label = { Text("$label$readySuffix", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("Hinglish Script Output", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = SecondMindColors.PrimaryInk)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UoaiAsrScript.entries.forEach { script ->
                        FilterChip(
                            selected = settings.uoaiAsrScript == script,
                            onClick = { onSetUoaiAsrScript(script) },
                            colors = cardChipColors,
                            border = null,
                            shape = RoundedCornerShape(50),
                            label = { Text(script.displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                val activeNote = providerById[settings.uoaiAsrProviderId.lowercase()]?.note
                    ?: when (settings.uoaiAsrProviderId.lowercase()) {
                        UoaiConstants.AUDIO_PROVIDER_GEMMA4 ->
                            "Direct Gemma 4 E2B/E4B Conformer audio encoder via http://127.0.0.1:8765/v1/audio/transcriptions (best Hinglish & multilingual fidelity)."
                        UoaiConstants.AUDIO_PROVIDER_WHISPER ->
                            "Dedicated offline whisper.cpp (99 languages). Preload fast/balanced/accurate below or inside UOAI Provider."
                        UoaiConstants.AUDIO_PROVIDER_CACTUS ->
                            "Lightweight 4-bit Hindi/Hinglish Cactus Whistle (akshar-v4 / shruti-hi, ~64–80 MB RAM)."
                        UoaiConstants.AUDIO_PROVIDER_AUTO ->
                            "Lets UOAI Provider route automatically (active Whisper if loaded, otherwise direct Gemma 4)."
                        else ->
                            "Routes audio through your configured provider '${settings.uoaiAsrProviderId}' in UOAI Provider."
                    }
                Text(
                    text = activeNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )

                if (audioStatus != null) {
                    Text(
                        text = "UOAI Audio Status • Preferred: ${audioStatus.preferredProvider} • Queue: ${audioStatus.queueDepth} • Max: ${audioStatus.maxUploadBytes / (1024L * 1024L)} MiB / ${audioStatus.maxAudioDurationSec / 60}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.MutedText
                    )
                }

                if (settings.uoaiAsrProviderId.equals(UoaiConstants.AUDIO_PROVIDER_WHISPER, ignoreCase = true) ||
                    settings.uoaiAsrProviderId.equals(UoaiConstants.AUDIO_PROVIDER_CACTUS, ignoreCase = true)
                ) {
                    val quickModels = if (settings.uoaiAsrProviderId.equals(UoaiConstants.AUDIO_PROVIDER_WHISPER, ignoreCase = true)) {
                        listOf("fast" to "Load Tiny", "balanced" to "Load Base", "accurate" to "Load Small")
                    } else {
                        listOf("akshar-v4" to "Load Akshar v4", "akshar-lite-v4" to "Load Lite", "shruti-hi" to "Load Shruti")
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickModels.forEach { (modelKey, btnLabel) ->
                            SoftPillButton(
                                text = btnLabel,
                                compact = true,
                                enabled = !uiState.isManagingUoaiAudioModel,
                                onClick = { onLoadUoaiAudioModel(modelKey) }
                            )
                        }
                        TextButton(
                            onClick = onUnloadUoaiAudioModels,
                            enabled = !uiState.isManagingUoaiAudioModel
                        ) {
                            Text("Unload", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (val conn = uiState.uoaiConnection) {
                        is UoaiConnectionState.NeedsRegistration -> {
                            SoftPillButton(
                                text = "Approve UOAI Access",
                                compact = true,
                                containerColor = SecondMindColors.PrimaryInk,
                                contentColor = Color.White,
                                onClick = { onLaunchUoaiRegistration(conn.registrationIntent) }
                            )
                        }
                        is UoaiConnectionState.ProviderNotRunning -> {
                            SoftPillButton(
                                text = "Open UOAI Provider",
                                compact = true,
                                containerColor = SecondMindColors.PrimaryInk,
                                contentColor = Color.White,
                                onClick = { onOpenUoaiProvider(conn.launchProviderIntent) }
                            )
                        }
                        is UoaiConnectionState.ModelNotReady -> {
                            SoftPillButton(
                                text = "Open UOAI & Load Model",
                                compact = true,
                                containerColor = SecondMindColors.PrimaryInk,
                                contentColor = Color.White,
                                onClick = { onOpenUoaiProvider(conn.launchProviderIntent) }
                            )
                        }
                        is UoaiConnectionState.Error -> {
                            if (conn.launchProviderIntent != null) {
                                SoftPillButton(
                                    text = "Open UOAI Provider",
                                    compact = true,
                                    onClick = { onOpenUoaiProvider(conn.launchProviderIntent) }
                                )
                            }
                        }
                        else -> Unit
                    }
                    SoftPillButton(
                        text = "Refresh UOAI Audio",
                        icon = Icons.Default.Refresh,
                        compact = true,
                        onClick = onRefreshUoaiConnection
                    )
                }
            }
            if (settings.asrProviderType == AsrProviderType.ANDROID_ON_DEVICE) {
                val packs = uiState.offlineSpeechPacks
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Offline Speech Packs", fontWeight = FontWeight.SemiBold, color = SecondMindColors.PrimaryInk)
                    StatusPill(
                        label = when {
                            packs.isDownloading -> "Downloading..."
                            packs.hasAnyInstalledPack -> "${packs.installedLanguages.size} Installed"
                            !packs.isSupportedByDevice -> "Unavailable"
                            else -> "Needs Pack"
                        },
                        dotColor = when {
                            packs.hasAnyInstalledPack -> SecondMindColors.EmeraldPrivacy
                            packs.isDownloading -> SecondMindColors.CyanAccent
                            else -> SecondMindColors.AmberHighlight
                        },
                        containerColor = SecondMindColors.CardElevated
                    )
                }
                Text(
                    text = when {
                        packs.installedLanguages.isNotEmpty() ->
                            "Installed: ${packs.installedLanguages.joinToString(", ")}"
                        packs.pendingLanguages.isNotEmpty() ->
                            "Downloading: ${packs.pendingLanguages.joinToString(", ")}"
                        else ->
                            packs.lastMessage ?: "No offline packs detected yet. Tap Download EN/HI Packs on Wi-Fi."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )
                if (packs.isDownloading) {
                    val pct = packs.downloadProgressPercent
                    if (pct != null && pct > 0) {
                        LinearProgressIndicator(
                            progress = { (pct / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoftPillButton(
                        text = if (packs.isDownloading) "Downloading..." else "Download EN/HI Packs",
                        icon = Icons.Default.Download,
                        compact = true,
                        enabled = !packs.isDownloading && packs.isSupportedByDevice,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = onDownloadOfflineSpeechPacks
                    )
                    SoftPillButton(
                        text = "Refresh",
                        icon = Icons.Default.Refresh,
                        compact = true,
                        enabled = !packs.isChecking && !packs.isDownloading,
                        onClick = onRefreshOfflineSpeechPacks
                    )
                }
            }
            if (settings.asrProviderType == AsrProviderType.LOCAL_WHISPER_SERVER) {
                val whisperFocus = LocalFocusManager.current
                OutlinedTextField(
                    value = localWhisperInput,
                    onValueChange = { localWhisperInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = galleryTextFieldColors(containerColor = SecondMindColors.CardElevated),
                    label = { Text("Loopback Whisper URL (http://127.0.0.1:8080)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        onSetLocalWhisperUrl(localWhisperInput)
                        whisperFocus.clearFocus()
                    })
                )
                SoftPillButton(
                    text = "Save Loopback URL",
                    compact = true,
                    onClick = {
                        onSetLocalWhisperUrl(localWhisperInput)
                        whisperFocus.clearFocus()
                    }
                )
            }
        }

        GallerySectionLabel("Privacy & Data Management")
        Button(
            onClick = { confirmWipe = true },
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = SecondMindColors.HeroCoralSoft,
                contentColor = SecondMindColors.CoralAlert
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Delete All Local Audio, Transcripts & Memories", fontWeight = FontWeight.Medium)
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = SecondMindColors.CardSurface,
            title = { Text("Delete all local data on Primary?") },
            text = { Text("This deletes all encrypted audio, transcripts, extracted memories, tasks, summaries, and Q&A history on this Primary device. Your Secondary Recorder keeps its own files.") },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; onWipeAllLocalData() }) {
                    Text("Delete All", color = SecondMindColors.CoralAlert)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmSwitchRole) {
        AlertDialog(
            onDismissRequest = { confirmSwitchRole = false },
            containerColor = SecondMindColors.CardSurface,
            title = { Text("Switch to Secondary Recorder Mode?") },
            text = { Text("Active Bluetooth sync and audio playback will stop cleanly. All transcripts, memories, and audio chunks on this device remain preserved so you can switch back to Primary anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSwitchRole = false
                    onSwitchToSecondary()
                }) { Text("Switch to Secondary", color = SecondMindColors.CyanAccent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSwitchRole = false }) { Text("Cancel") }
            }
        )
    }
}
