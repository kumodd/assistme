package com.personalai.primary.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalai.primary.asr.AsrLanguageMode
import com.personalai.primary.asr.AsrProviderType
import com.personalai.primary.audio.AudioRetentionPolicy
import com.personalai.primary.audio.ExportedAudioResult
import com.personalai.primary.data.db.AsrProcessingStatus
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.SummaryType
import com.personalai.primary.data.db.SyncedSegmentEntity
import com.personalai.primary.data.db.TaskReminderStatus
import com.personalai.primary.memory.LlmProviderType
import com.personalai.primary.memory.uoai.UoaiConnectionState
import com.personalai.primary.ui.MemoryVaultFilter
import com.personalai.primary.ui.PrimaryUiState
import com.personalai.primary.ui.theme.SecondMindColors
import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.RecorderConfigPayload
import com.personalai.protocol.RecorderState
import com.personalai.protocol.StopMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrimaryAppShell(
    uiState: PrimaryUiState,
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
    onHighlightTranscript: (String?) -> Unit,
    onSetAsrProvider: (AsrProviderType) -> Unit,
    onSetAsrLanguage: (AsrLanguageMode) -> Unit,
    onSetLlmProvider: (LlmProviderType) -> Unit,
    onLaunchUoaiRegistration: (Intent) -> Unit,
    onOpenUoaiProvider: (Intent?) -> Unit,
    onRefreshUoaiConnection: () -> Unit,
    onClearUoaiCredentials: () -> Unit,
    onSetAutoExtractMemory: (Boolean) -> Unit,
    onSetLocalWhisperUrl: (String) -> Unit,
    onSetAutoSync: (Boolean) -> Unit,
    onSetAutoTranscribe: (Boolean) -> Unit,
    onWipeAllLocalData: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var deviceSubTab by rememberSaveable { mutableIntStateOf(2) } // 0=Controls, 1=Pairing, 2=AI & Storage
    var memoryHubInitialSubTab by rememberSaveable { mutableIntStateOf(0) } // 0=Vault, 1=Insights, 2=Digests, 3=Tasks
    var showQuickCaptureOnHome by remember { mutableStateOf(false) }

    val openTranscriptSource: (String) -> Unit = { transcriptId ->
        onHighlightTranscript(transcriptId)
        selectedTab = 3
    }

    Scaffold(
        containerColor = SecondMindColors.Canvas,
        bottomBar = {
            NavigationBar(containerColor = SecondMindColors.CardSurface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Today, contentDescription = null) },
                    label = { Text("Today") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                    label = { Text("Ask") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Psychology, contentDescription = null) },
                    label = { Text("2nd Mind") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                    label = { Text("Audio") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Export / Storage Notice Banner (e.g., Download WAV to Downloads/SecondMind + Share button)
            uiState.storageNotice?.let { notice ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SecondMindColors.EmeraldContainer),
                    border = BorderStroke(1.dp, SecondMindColors.EmeraldPrivacy.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = notice,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        uiState.lastExportedAudio?.let { exported ->
                            TextButton(onClick = { onShareExportedAudio(exported) }) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(" Share")
                            }
                        }
                        IconButton(
                            onClick = {
                                onClearStorageNotice()
                                onClearExportFeedback()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            uiState.lastFeedback?.let { fb ->
                if (!fb.success) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${fb.commandType}: ${fb.message}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = onClearFeedback) { Text("OK") }
                        }
                    }
                }
            }

            uiState.lastError?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(err, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            when (selectedTab) {
                0 -> TodayBriefingTab(
                    uiState = uiState,
                    onConnectPaired = onConnectPaired,
                    onDisconnect = onDisconnect,
                    onRefreshAll = onRefreshAll,
                    onSyncNow = onSyncNow,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onSetLocalOnlyPauseSync = onSetLocalOnlyPauseSync,
                    onToggleTaskDone = { id, done ->
                        onUpdateTaskStatus(id, if (done) TaskReminderStatus.DONE else TaskReminderStatus.OPEN)
                    },
                    onCaptureThoughtClick = { showQuickCaptureOnHome = true },
                    onGenerateDailyDigest = {
                        onGenerateSummary(SummaryType.DAILY)
                        memoryHubInitialSubTab = 2
                        selectedTab = 2
                    },
                    onOpenPairing = { deviceSubTab = 1; selectedTab = 4 },
                    onOpenAskAi = { selectedTab = 1 },
                    onOpenVault = { memoryHubInitialSubTab = 0; selectedTab = 2 },
                    onOpenInsights = { memoryHubInitialSubTab = 1; selectedTab = 2 },
                    onOpenTasks = { memoryHubInitialSubTab = 3; selectedTab = 2 },
                    onOpenAudio = { selectedTab = 3 },
                    onOpenAiSetup = { deviceSubTab = 2; selectedTab = 4 }
                )
                1 -> AskAiTab(
                    uiState = uiState,
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
                    initialSubTab = memoryHubInitialSubTab,
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
                    onInspectSourceTranscript = openTranscriptSource,
                    onOpenAudioStorage = { selectedTab = 3 }
                )
                3 -> AudioAndTranscriptsTab(
                    uiState = uiState,
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
                    onSetAutoSync = onSetAutoSync,
                    onSetAutoTranscribe = onSetAutoTranscribe,
                    onWipeAllLocalData = onWipeAllLocalData
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Executive 2nd Mind Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "${ins.greeting} — 2nd Mind",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "100% private offline memory • ${formatDurationFriendly(ins.todaySpokenSeconds)} captured today",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.MutedText
                )
            }
        }

        // Compact Live Status Strip (BLE + Mic + Offline Gemma 4)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val uoaiConn = uiState.uoaiConnection
            StatusPill(
                label = when (uoaiConn) {
                    is UoaiConnectionState.Connected -> "Gemma Offline Ready"
                    is UoaiConnectionState.ModelNotReady -> "AI Loading..."
                    else -> "Setup Offline AI"
                },
                dotColor = if (uoaiConn is UoaiConnectionState.Connected) SecondMindColors.EmeraldPrivacy else SecondMindColors.AmberHighlight,
                containerColor = if (uoaiConn is UoaiConnectionState.Connected) SecondMindColors.EmeraldContainer else SecondMindColors.AmberContainer,
                onClick = onOpenAiSetup
            )
            StatusPill(
                label = "BLE: ${uiState.bleState.name}",
                dotColor = when (uiState.bleState) {
                    BleConnectionState.READY, BleConnectionState.SYNCHRONIZING -> SecondMindColors.CyanAccent
                    else -> SecondMindColors.MutedText
                },
                onClick = if (uiState.pairedDevice == null) onOpenPairing else null
            )
            StatusPill(
                label = "Mic: ${recState.name.replace('_', ' ')}",
                dotColor = if (recState.isArmedOrCapturing) SecondMindColors.EmeraldPrivacy else SecondMindColors.MutedText
            )
        }

        // Today's 2nd Mind Pulse (4 interactive KPI tiles — concise labels fit 360dp phones without truncation)
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
                accent = SecondMindColors.VioletAi,
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

        // Quick Productivity Actions
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onCaptureThoughtClick, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Capture")
            }
            OutlinedButton(onClick = onOpenAskAi, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Ask 2nd Mind")
            }
            OutlinedButton(onClick = onGenerateDailyDigest, shape = RoundedCornerShape(12.dp)) {
                Text("Daily Digest")
            }
            OutlinedButton(onClick = onOpenInsights, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Insights, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Insights")
            }
        }

        // Live Pipeline Activity (only shown when active or error)
        if (sync.isSyncing || asr.isTranscribing || uiState.memoryStatus.isExtracting || !sync.lastError.isNullOrBlank()) {
            MindSurfaceCard(borderColor = SecondMindColors.CyanAccent.copy(alpha = 0.45f)) {
                Text("Live Processing Pipeline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (sync.isSyncing) {
                    Text(
                        "Syncing #${sync.activeSegmentSeq ?: "-"} via ${sync.transportLabel ?: "BLE"} (${sync.activeBytesReceived / 1024}/${sync.activeBytesTotal / 1024} KB)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val progress = if (sync.activeBytesTotal > 0) {
                        (sync.activeBytesReceived.toFloat() / sync.activeBytesTotal.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                if (asr.isTranscribing) {
                    Text("Transcribing #${asr.activeSegmentSequence ?: "-"} offline...", style = MaterialTheme.typography.bodySmall, color = SecondMindColors.CyanAccent)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (uiState.memoryStatus.isExtracting) {
                    Text("Distilling memories with Gemma offline...", style = MaterialTheme.typography.bodySmall, color = SecondMindColors.VioletAi)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SecondMindColors.VioletAi)
                }
                sync.lastError?.let { Text("Sync Error: $it", color = SecondMindColors.CoralAlert, style = MaterialTheme.typography.bodySmall) }
            }
        }

        // Focus Tasks Spotlight (Interactive directly on Today!)
        MindSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SecondMindColors.AmberHighlight, modifier = Modifier.size(18.dp))
                    Text("Focus Tasks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onOpenTasks) { Text("All (${uiState.tasks.size})") }
            }
            if (openTasks.isEmpty()) {
                Text(
                    "All clear! Spoken commitments & captured tasks appear here automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.MutedText
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
                            Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            MindSurfaceCard(borderColor = SecondMindColors.AmberHighlight.copy(alpha = 0.4f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.PushPin, contentDescription = null, tint = SecondMindColors.AmberHighlight, modifier = Modifier.size(16.dp))
                        Text("Pinned in 2nd Mind", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onOpenVault) { Text("Open Vault") }
                }
                pinnedMemories.forEach { mem ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(mem.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            mem.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.MutedText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Compact Companion Recorder & Sync Strip
        MindSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = SecondMindColors.CyanAccent, modifier = Modifier.size(18.dp))
                    Column {
                        Text(
                            uiState.pairedDevice?.deviceName ?: "No Paired Device B",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
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
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.pairedDevice == null) {
                    Button(onClick = onOpenPairing) { Text("Pair Device B") }
                } else if (uiState.bleState == BleConnectionState.DISCONNECTED) {
                    Button(onClick = onConnectPaired) { Text("Connect") }
                } else {
                    if (isReady) {
                        Button(onClick = onSyncNow, enabled = !sync.isSyncing) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(" Sync")
                        }
                        OutlinedButton(onClick = onStartRecording) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(" Resume")
                        }
                        OutlinedButton(
                            onClick = { onStopRecording(StopMode.STOP_SERVICE) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondMindColors.CoralAlert)
                        ) { Text("Stop Mic") }
                    }
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Pause Sync Only (Keep Recording)", style = MaterialTheme.typography.bodySmall, color = SecondMindColors.MutedText)
                Switch(
                    checked = uiState.remoteConfig.localOnlyPauseSync,
                    onCheckedChange = onSetLocalOnlyPauseSync,
                    enabled = isReady
                )
            }
        }
    }
}

@Composable
private fun AudioAndTranscriptsTab(
    uiState: PrimaryUiState,
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
    onClearHighlight: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
    val speechPacks = uiState.offlineSpeechPacks
    val hasPackError = remember(uiState.segmentsWithTranscripts) {
        uiState.segmentsWithTranscripts.any { item ->
            val err = item.segment.asrError.orEmpty()
            item.segment.asrStatus == AsrProcessingStatus.FAILED &&
                (err.contains("pack", ignoreCase = true) || err.contains("language", ignoreCase = true))
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Audio Chunks & Transcripts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Play, download specific chunks to Downloads/SecondMind, or auto-prune old audio while keeping transcripts forever.",
                style = MaterialTheme.typography.labelSmall,
                color = SecondMindColors.MutedText
            )
        }

        // Audio Chunk Auto-Deletion (1d / 3d / 5d / Keep All) Strip
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
                    Text("Highlighting cited source transcript", color = SecondMindColors.CyanAccent, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = onClearHighlight) { Text("Clear") }
                }
            }
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("Search transcripts or chunk #...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
            Row(
                modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "ALL" to "All",
                    "AUDIO_ONLY" to "Has Audio",
                    "EN" to "English",
                    "HI" to "Hindi",
                    "PENDING" to "Pending"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = uiState.languageFilter == key,
                        onClick = { onLanguageFilterChange(key) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSyncNow,
                    enabled = uiState.bleState == BleConnectionState.READY && !uiState.syncProgress.isSyncing
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Sync Audio")
                }
                OutlinedButton(onClick = onRunPendingAsrNow) { Text("Transcribe") }
                TextButton(onClick = onRetryAllFailedAsr) { Text("Retry Failed") }
                if (uiState.appSettings.asrProviderType == AsrProviderType.ANDROID_ON_DEVICE &&
                    (hasPackError || !speechPacks.hasAnyInstalledPack)
                ) {
                    OutlinedButton(
                        onClick = onDownloadOfflineSpeechPacks,
                        enabled = !speechPacks.isDownloading
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(if (speechPacks.isDownloading) " Downloading..." else " Speech Pack")
                    }
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
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            if (uiState.asrProgress.isTranscribing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }

        if (uiState.segmentsWithTranscripts.isEmpty()) {
            item {
                MindSurfaceCard {
                    Text("No synced audio chunks match yet.", fontWeight = FontWeight.SemiBold)
                    Text(
                        "1. Arm recording on Device B and speak.\n2. Connect Device A and tap Sync Audio.\n3. On-Device Speech transcribes offline automatically (zero cloud).",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.MutedText
                    )
                    OutlinedButton(onClick = onOpenSettings) { Text("Open AI & Storage Settings") }
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
                    containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else SecondMindColors.CardSurface,
                    borderColor = if (isHighlighted) SecondMindColors.CyanAccent else SecondMindColors.HairlineBorder
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "#${seg.sequenceNumber} • ${formatter.format(Date(seg.startTimestampMs))} (${"%.1f".format(seg.durationMs / 1000.0)}s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = SecondMindColors.CyanAccent
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(
                                label = if (seg.audioDeleted) "Audio Pruned" else formatStorageBytes(seg.wavSizeBytes),
                                dotColor = if (seg.audioDeleted) SecondMindColors.MutedText else SecondMindColors.EmeraldPrivacy
                            )
                            Text(
                                text = tr?.language?.uppercase() ?: seg.asrStatus.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (tr != null) {
                        SelectionContainer { Text(tr.text, style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            text = "ASR: ${tr.asrProvider} (${(tr.confidence * 100).toInt()}%) • Memory: ${tr.memoryExtractionStatus}",
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
                            color = if (seg.asrStatus == AsrProcessingStatus.FAILED) SecondMindColors.CoralAlert else SecondMindColors.MutedText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (isPlaying) {
                        LinearProgressIndicator(
                            progress = { uiState.playbackProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = SecondMindColors.CyanAccent
                        )
                    }

                    if (!seg.audioDeleted) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { onToggleAudioPlayback(seg) }) {
                                Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (isPlaying) " Stop" else " Play", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onDownloadSpecificChunk(seg) },
                                enabled = !isExporting
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (isExporting) " Saving..." else " Save WAV", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onDeleteSpecificChunkAudio(seg) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = SecondMindColors.CoralAlert, modifier = Modifier.size(16.dp))
                                Text(" Delete Audio", style = MaterialTheme.typography.labelSmall, color = SecondMindColors.CoralAlert)
                            }
                            TextButton(onClick = { onRetrySegmentAsr(seg.segmentId) }) {
                                Text(if (tr == null) "Transcribe" else "Re-ASR", style = MaterialTheme.typography.labelSmall)
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
        }
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
    onSetAutoSync: (Boolean) -> Unit,
    onSetAutoTranscribe: (Boolean) -> Unit,
    onWipeAllLocalData: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = selectedSubTab == 2, onClick = { onSelectSubTab(2) }, label = { Text("AI & Storage") })
            FilterChip(selected = selectedSubTab == 0, onClick = { onSelectSubTab(0) }, label = { Text("Controls") })
            FilterChip(selected = selectedSubTab == 1, onClick = { onSelectSubTab(1) }, label = { Text("Pairing") })
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
                onSetAutoSync = onSetAutoSync,
                onSetAutoTranscribe = onSetAutoTranscribe,
                onWipeAllLocalData = onWipeAllLocalData
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!uiState.permissions.canUseBle) {
            item {
                MindSurfaceCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text("Bluetooth Permissions Required", fontWeight = FontWeight.Bold)
                    uiState.permissions.items.filter { !it.granted }.forEach {
                        Text("• ${it.title}: ${it.rationale}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestPermissions) { Text("Grant Permissions") }
                        OutlinedButton(onClick = onOpenAppSettings) { Text("Settings") }
                    }
                }
            }
        }

        uiState.pairedDevice?.let { paired ->
            item {
                MindSurfaceCard {
                    Text("Currently Paired Recorder", fontWeight = FontWeight.SemiBold)
                    Text("${paired.deviceName} • ID=${paired.deviceId}")
                    Text("Last BLE Addr: ${paired.bleAddress}", style = MaterialTheme.typography.bodySmall, color = SecondMindColors.MutedText)
                    OutlinedButton(onClick = onUnpair) { Text("Unpair Device B") }
                }
            }
        }

        uiState.pendingSas?.let { sas ->
            item {
                MindSurfaceCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Verify 6-Digit Pairing Code on Both Phones", fontWeight = FontWeight.Bold)
                    Text("Recorder: ${sas.serverDeviceName} (${sas.serverDeviceId.take(8)}…)")
                    Text(sas.sasCode, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Approve on Device B as well, then tap Confirm.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirmSas) { Text("Confirm SAS Match") }
                        OutlinedButton(onClick = onCancelSas) { Text("Cancel") }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!uiState.isScanning) {
                    Button(onClick = onStartScan, enabled = uiState.permissions.canUseBle) { Text("Scan for Device B") }
                } else {
                    OutlinedButton(onClick = onStopScan) { Text("Stop Scan") }
                }
            }
            if (uiState.isScanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }

        items(uiState.discoveredDevices, key = { it.address }) { dev ->
            MindSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dev.name, fontWeight = FontWeight.SemiBold)
                        Text("Addr: ${dev.address} • RSSI: ${dev.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = SecondMindColors.MutedText)
                        dev.deviceTagHex?.let { Text("App Tag: $it", style = MaterialTheme.typography.labelSmall) }
                    }
                    Button(onClick = { onBeginPairing(dev.address) }) { Text("Pair") }
                }
            }
        }
    }
}

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
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MindSurfaceCard {
                Text("Remote Recorder Controls", fontWeight = FontWeight.SemiBold)
                Text(
                    "Android 12–17 requires Device B's microphone service to be armed visibly on Device B first; remote Start resumes armed standby.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.MutedText
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartRecording, enabled = isReady) { Text("Start / Resume") }
                    OutlinedButton(onClick = { onStopRecording(StopMode.STANDBY_PAUSE) }, enabled = isReady) { Text("Pause Standby") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onStopRecording(StopMode.STOP_SERVICE) },
                        enabled = isReady,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Stop Mic") }
                    OutlinedButton(onClick = onPing, enabled = isReady) { Text("Ping") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshAll, enabled = isReady) { Text("Query Status") }
                    Button(onClick = onSyncNow, enabled = isReady && !uiState.syncProgress.isSyncing) { Text("Sync Now") }
                }
            }
        }

        item {
            MindSurfaceCard {
                Text("Remote VAD & Sync Config", fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pause Sync & Transcripts Only")
                    Switch(checked = cfg.localOnlyPauseSync, onCheckedChange = onSetLocalOnlyPauseSync, enabled = isReady)
                }
                Text("VAD Threshold: ${"%.2f".format(cfg.vadThreshold)}")
                Slider(
                    value = cfg.vadThreshold,
                    onValueChange = { onSetConfig(cfg.copy(vadThreshold = it)) },
                    valueRange = 0.20f..0.85f,
                    enabled = isReady
                )
            }
        }

        item { Text("Command Audit Log", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }

        items(uiState.auditLog, key = { it.commandId }) { item ->
            MindSurfaceCard {
                Text(
                    text = "${formatter.format(Date(item.sentAtMs))} • ${item.commandType} • ${if (item.success) "OK" else item.errorCode} (${item.rttMs}ms)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.success) SecondMindColors.EmeraldPrivacy else SecondMindColors.CoralAlert
                )
                Text(item.message, style = MaterialTheme.typography.bodySmall, color = SecondMindColors.MutedText)
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
    onSetAutoSync: (Boolean) -> Unit,
    onSetAutoTranscribe: (Boolean) -> Unit,
    onWipeAllLocalData: () -> Unit
) {
    val settings = uiState.appSettings
    val batt = uiState.remoteBattery
    val storage = uiState.remoteStorage
    val info = uiState.remoteDeviceInfo

    var localWhisperInput by rememberSaveable(settings.localWhisperBaseUrl) { mutableStateOf(settings.localWhisperBaseUrl) }
    var confirmWipe by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MindSurfaceCard(
            containerColor = SecondMindColors.EmeraldContainer,
            borderColor = SecondMindColors.EmeraldPrivacy.copy(alpha = 0.45f)
        ) {
            Text("100% On-Device Privacy Architecture", fontWeight = FontWeight.Bold, color = SecondMindColors.EmeraldPrivacy)
            Text(
                "Zero cloud AI APIs and zero external API keys. Audio, transcripts, memories, and LLM prompts never leave this phone — inference runs locally via Universal Offline AI Provider (127.0.0.1:8765).",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Audio Chunk Auto-Deletion (1d / 3d / 5d / Keep All)
        AudioRetentionBarCard(
            policy = settings.audioRetentionPolicy,
            activeAudioChunkCount = uiState.activeAudioChunkCount,
            activeAudioBytes = uiState.activeAudioBytes,
            onSelectPolicy = onSetAudioRetentionPolicy,
            onPurgeExpiredNow = onPurgeExpiredAudioNow,
            compact = false
        )

        Text("Offline AI & LLM Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        MindSurfaceCard {
            LlmProviderType.entries.forEach { llm ->
                FilterChip(
                    selected = settings.llmProviderType == llm,
                    onClick = { onSetLlmProvider(llm) },
                    label = { Text(llm.displayName) }
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

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Auto-Sync on BLE Reconnect")
                Switch(checked = settings.autoSyncOnReconnect, onCheckedChange = onSetAutoSync)
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Auto-Transcribe Synced Audio")
                Switch(checked = settings.autoTranscribeSynced, onCheckedChange = onSetAutoTranscribe)
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Auto-Extract Personal Memories")
                Switch(checked = settings.autoExtractMemory, onCheckedChange = onSetAutoExtractMemory)
            }
        }

        Text("Offline Speech Recognition (ASR)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        MindSurfaceCard {
            AsrProviderType.entries.forEach { provider ->
                FilterChip(
                    selected = settings.asrProviderType == provider,
                    onClick = { onSetAsrProvider(provider) },
                    label = { Text(provider.displayName) }
                )
            }
            Text("Language Mode", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AsrLanguageMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.asrLanguageMode == mode,
                        onClick = { onSetAsrLanguage(mode) },
                        label = { Text(mode.displayName) }
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
                    Text("Offline Speech Packs", fontWeight = FontWeight.SemiBold)
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
                        }
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
                    color = SecondMindColors.MutedText
                )
                if (packs.isDownloading) {
                    val pct = packs.downloadProgressPercent
                    if (pct != null && pct > 0) {
                        LinearProgressIndicator(
                            progress = { (pct / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onDownloadOfflineSpeechPacks,
                        enabled = !packs.isDownloading && packs.isSupportedByDevice
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(if (packs.isDownloading) " Downloading..." else " Download EN/HI Packs")
                    }
                    OutlinedButton(
                        onClick = onRefreshOfflineSpeechPacks,
                        enabled = !packs.isChecking && !packs.isDownloading
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" Refresh")
                    }
                }
            }
            if (settings.asrProviderType == AsrProviderType.LOCAL_WHISPER_SERVER) {
                OutlinedTextField(
                    value = localWhisperInput,
                    onValueChange = { localWhisperInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Loopback Whisper URL (http://127.0.0.1:8080)") },
                    singleLine = true
                )
                OutlinedButton(onClick = { onSetLocalWhisperUrl(localWhisperInput) }) { Text("Save Loopback URL") }
            }
        }

        Text("Device B Health & Specs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        MindSurfaceCard {
            Text("Battery: ${batt?.batteryPct ?: uiState.remoteStatus?.batteryPct ?: -1}% • Charging: ${batt?.isCharging ?: false}")
            Text("Power Save: ${batt?.isPowerSaveMode ?: false} • Thermal: ${batt?.thermalStatus ?: 0}")
            Text("Free Storage: ${(storage?.freeBytes ?: 0L) / (1024L * 1024L)} / ${(storage?.totalBytes ?: 0L) / (1024L * 1024L)} MB")
            Text("Device Model: ${info?.let { "${it.manufacturer} ${it.model} (SDK ${it.androidSdk})" } ?: "-"}")
            Text("Sync Cursor Sequence: #${settings.lastSyncedSequence}")
            OutlinedButton(onClick = onRefreshAll, enabled = uiState.bleState == BleConnectionState.READY) {
                Text("Refresh Device B Telemetry")
            }
        }

        Text("Privacy & Data Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(
            onClick = { confirmWipe = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        ) {
            Icon(Icons.Default.DeleteForever, contentDescription = null)
            Text(" Delete All Local Audio, Transcripts & Memories")
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all local data on Device A?") },
            text = { Text("This deletes all encrypted audio, transcripts, extracted memories, tasks, summaries, and Q&A history on Device A. Device B keeps its own files.") },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; onWipeAllLocalData() }) { Text("Delete All") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Cancel") }
            }
        )
    }
}
