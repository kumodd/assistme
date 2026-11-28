package com.personalai.primary.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalai.primary.data.db.CertaintyLevel
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.SummaryType
import com.personalai.primary.data.db.TaskReminderEntity
import com.personalai.primary.data.db.TaskReminderStatus
import com.personalai.primary.memory.LlmProviderType
import com.personalai.primary.memory.uoai.UoaiConnectionState
import com.personalai.primary.ui.MemoryVaultFilter
import com.personalai.primary.ui.PrimaryUiState
import com.personalai.primary.ui.theme.SecondMindColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UoaiConnectionStatusCard(
    connection: UoaiConnectionState?,
    notice: String?,
    onLaunchRegistration: (Intent) -> Unit,
    onOpenProvider: (Intent?) -> Unit,
    onRefresh: () -> Unit,
    onClearCredentials: () -> Unit,
    compact: Boolean = false
) {
    val isConnected = connection is UoaiConnectionState.Connected
    var confirmClearToken by remember { mutableStateOf(false) }

    MindSurfaceCard(
        containerColor = if (isConnected) SecondMindColors.EmeraldContainer else SecondMindColors.CardSurface,
        borderColor = if (isConnected) SecondMindColors.EmeraldPrivacy.copy(alpha = 0.3f) else SecondMindColors.HairlineBorder
    ) {
        if (isConnected) {
            StarEyebrowBadge(label = "Connected on-device (127.0.0.1:8765)")
        }
        Text(
            "Universal Offline AI Provider",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = SecondMindColors.PrimaryInk
        )
        when (connection) {
            null -> Text(
                "Checking local UOAI loopback (127.0.0.1:8765)...",
                style = MaterialTheme.typography.bodySmall,
                color = SecondMindColors.SecondaryInk
            )
            UoaiConnectionState.ProviderNotInstalled -> {
                Text(
                    "Universal Offline AI Provider (com.uoai.provider) is not installed. Install it for 100% offline Gemma 4 LLM & Speech-to-Text, or use the built-in offline rule engine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )
                SoftPillButton(text = "Re-check", icon = Icons.Default.Refresh, compact = true, onClick = onRefresh)
            }
            is UoaiConnectionState.NeedsRegistration -> {
                Text(
                    "One-time OS-verified approval required to connect to your offline Gemma 4 LLM & Speech-to-Text provider (approve speech_to_text for UOAI ASR).",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftPillButton(
                        text = "Approve UOAI Access",
                        compact = true,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = { onLaunchRegistration(connection.registrationIntent) }
                    )
                    SoftPillButton(text = "Refresh", compact = true, onClick = onRefresh)
                }
            }
            is UoaiConnectionState.ProviderNotRunning -> {
                Text(
                    "UOAI Provider service is stopped. Open the provider app and start the local API server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftPillButton(
                        text = "Open UOAI Provider",
                        compact = true,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = { onOpenProvider(connection.launchProviderIntent) }
                    )
                    SoftPillButton(text = "Refresh", compact = true, onClick = onRefresh)
                }
            }
            is UoaiConnectionState.ModelNotReady -> {
                Text(
                    "UOAI model is not loaded yet (state: ${formatHumanEnum(connection.modelState)}).${connection.modelError?.let { " $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.SecondaryInk
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftPillButton(
                        text = "Open & Load Model",
                        compact = true,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = { onOpenProvider(connection.launchProviderIntent) }
                    )
                    SoftPillButton(text = "Refresh", compact = true, onClick = onRefresh)
                }
            }
            is UoaiConnectionState.Connected -> {
                val modelLabel = connection.activeModel?.name?.ifBlank { connection.activeModel.id } ?: "auto"
                Text(
                    "Model: $modelLabel • Context: ${connection.capabilities.contextWindowTokens} tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.EmeraldPrivacy,
                    fontWeight = FontWeight.Medium
                )
                if (!compact) {
                    Text(
                        "Capabilities: ${connection.capabilities.features.joinToString(", ").ifBlank { "chat" }} • Max Output: ${connection.capabilities.maxTokensLimit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.SecondaryInk
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SoftPillButton(
                            text = "Refresh",
                            icon = Icons.Default.Refresh,
                            compact = true,
                            containerColor = SecondMindColors.CardSurface,
                            onClick = onRefresh
                        )
                        TextButton(onClick = { confirmClearToken = true }) {
                            Text("Clear Token", color = SecondMindColors.CoralAlert)
                        }
                    }
                }
            }
            is UoaiConnectionState.Error -> {
                Text(
                    "UOAI Error${connection.errorCode?.let { " ($it)" } ?: ""}: ${connection.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.CoralAlert
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftPillButton(
                        text = "Retry",
                        compact = true,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = onRefresh
                    )
                    if (connection.launchProviderIntent != null) {
                        SoftPillButton(
                            text = "Open Provider",
                            compact = true,
                            onClick = { onOpenProvider(connection.launchProviderIntent) }
                        )
                    }
                }
            }
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SecondMindColors.CoralAlert)
        }
    }

    if (confirmClearToken) {
        AlertDialog(
            onDismissRequest = { confirmClearToken = false },
            containerColor = SecondMindColors.CardSurface,
            title = { Text("Clear UOAI access token?") },
            text = { Text("You will need to tap Approve UOAI Access again before using Gemma 4 LLM or UOAI Speech-to-Text.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearToken = false
                    onClearCredentials()
                }) { Text("Clear Token", color = SecondMindColors.CoralAlert) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearToken = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun AskAiTab(
    uiState: PrimaryUiState,
    listState: LazyListState = rememberLazyListState(),
    onAskQuestion: (String) -> Unit,
    onStopAskAi: () -> Unit,
    onLaunchUoaiRegistration: (Intent) -> Unit,
    onOpenUoaiProvider: (Intent?) -> Unit,
    onRefreshUoaiConnection: () -> Unit,
    onClearUoaiCredentials: () -> Unit,
    onInspectSourceTranscript: (String) -> Unit
) {
    var questionText by rememberSaveable { mutableStateOf("") }
    val formatter = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val focusManager = LocalFocusManager.current

    val quickPrompts = remember {
        listOf(
            "What did I discuss today?",
            "What did I promise to do?",
            "What deadlines are coming up?",
            "Key decisions & ideas this week",
            "Summarize my conversations this week"
        )
    }

    val submitQuestion = {
        val trimmed = questionText.trim()
        if (trimmed.isNotEmpty() && !uiState.memoryStatus.isAnswering) {
            onAskQuestion(trimmed)
            questionText = ""
            focusManager.clearFocus()
        }
    }

    val isFreshState = uiState.latestAskAiAnswer == null &&
        uiState.askAiHistory.isEmpty() &&
        uiState.streamingAskText.isBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isFreshState) {
                item {
                    val activeModelCaption = when {
                        uiState.appSettings.llmProviderType == LlmProviderType.LOCAL_HEURISTIC_OFFLINE ->
                            "Built-in Offline Rule Engine • ${uiState.memories.size} memories indexed"
                        uiState.uoaiConnection is UoaiConnectionState.Connected -> {
                            val name = uiState.uoaiConnection.activeModel?.name?.ifBlank { "Gemma 4" } ?: "Gemma 4"
                            "$name on-device • ${uiState.memories.size} memories indexed"
                        }
                        else -> "100% offline • ${uiState.memories.size} memories indexed"
                    }
                    GalleryHeroHeader(
                        icon = Icons.Default.AutoAwesome,
                        title = "Ask Sunbo",
                        subtitle = "Chat with your on-device 2nd Mind memories",
                        accentColor = SecondMindColors.HeroCoral,
                        metaCaption = activeModelCaption
                    )
                }
            }

            if (uiState.appSettings.llmProviderType == LlmProviderType.UOAI_OFFLINE &&
                uiState.uoaiConnection !is UoaiConnectionState.Connected
            ) {
                item {
                    UoaiConnectionStatusCard(
                        connection = uiState.uoaiConnection,
                        notice = uiState.uoaiNotice,
                        onLaunchRegistration = onLaunchUoaiRegistration,
                        onOpenProvider = onOpenUoaiProvider,
                        onRefresh = onRefreshUoaiConnection,
                        onClearCredentials = onClearUoaiCredentials,
                        compact = true
                    )
                }
            }

            if (uiState.memoryStatus.isAnswering) {
                item {
                    MindSurfaceCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = SecondMindColors.HeroCoral,
                                trackColor = SecondMindColors.HeroCoralSoft
                            )
                            SoftPillButton(
                                text = "Stop",
                                compact = true,
                                containerColor = SecondMindColors.HeroCoralSoft,
                                contentColor = SecondMindColors.HeroCoral,
                                onClick = onStopAskAi
                            )
                        }
                    }
                }
            }

            if (uiState.streamingAskText.isNotBlank()) {
                item {
                    MindSurfaceCard(
                        containerColor = SecondMindColors.CardSurface,
                        borderColor = SecondMindColors.VioletAi.copy(alpha = 0.35f)
                    ) {
                        StatusPill(
                            label = "Streaming offline response...",
                            dotColor = SecondMindColors.VioletAi,
                            containerColor = SecondMindColors.VioletContainer
                        )
                        MarkdownText(
                            text = uiState.streamingAskText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            uiState.latestAskAiAnswer?.let { latest ->
                item {
                    MindSurfaceCard(
                        containerColor = SecondMindColors.CardSurface,
                        borderColor = SecondMindColors.CyanAccent.copy(alpha = 0.3f)
                    ) {
                        StarEyebrowBadge(label = "Grounded answer • ${latest.providerUsed}")
                        Text(
                            text = latest.question,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SecondMindColors.PrimaryInk
                        )
                        SelectionContainer {
                            MarkdownText(
                                text = latest.answer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SecondMindColors.SecondaryInk
                            )
                        }

                        if (latest.citedSources.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Cited transcript sources",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = SecondMindColors.MutedText
                            )
                            latest.citedSources.forEach { src ->
                                val canOpen = src.transcriptId in uiState.spokenTranscriptIds
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable(enabled = canOpen) { onInspectSourceTranscript(src.transcriptId) },
                                    shape = RoundedCornerShape(16.dp),
                                    color = SecondMindColors.CardElevated
                                ) {
                                    Text(
                                        text = "[${src.citationTag} • ${src.formattedTime}] ${src.text.take(84)}${if (src.text.length > 84) "…" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (canOpen) SecondMindColors.CyanAccent else SecondMindColors.MutedText,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isFreshState) {
                item {
                    GallerySectionLabel("How Ask Sunbo works")
                }
                item {
                    MindSurfaceCard {
                        StarEyebrowBadge(label = "Strictly grounded • Zero cloud")
                        Text(
                            "Every answer cites your real memories",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SecondMindColors.PrimaryInk
                        )
                        Text(
                            "Ask about conversations, promises, decisions, or deadlines. Everything runs locally on your phone and links back to the exact spoken transcript.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.SecondaryInk
                        )
                    }
                }
            }

            if (uiState.askAiHistory.isNotEmpty()) {
                item {
                    GallerySectionLabel("Recent Q&A")
                }
                items(uiState.askAiHistory, key = { it.queryId }) { item ->
                    MindSurfaceCard {
                        Text(
                            "${formatter.format(Date(item.createdAtMs))} • ${item.providerUsed}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondMindColors.CyanAccent
                        )
                        Text(
                            item.question,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                            color = SecondMindColors.PrimaryInk
                        )
                        MarkdownText(
                            text = item.answer,
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.SecondaryInk
                        )
                    }
                }
            }
        }

        // Docked white bottom prompt strip + pill composer (always reachable above keyboard)
        Surface(
            color = SecondMindColors.TopBarSurface
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(thickness = 0.75.dp, color = SecondMindColors.HairlineBorder)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val promptChipColors = galleryFilterChipColors(onWhiteCard = true)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickPrompts.forEach { prompt ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    if (!uiState.memoryStatus.isAnswering) {
                                        onAskQuestion(prompt)
                                        focusManager.clearFocus()
                                    }
                                },
                                colors = promptChipColors,
                                border = null,
                                shape = RoundedCornerShape(50),
                                label = { Text(prompt, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = questionText,
                            onValueChange = { questionText = it },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                            colors = galleryTextFieldColors(containerColor = SecondMindColors.CardElevated),
                            placeholder = {
                                Text(
                                    "Ask about talks, promises, ideas...",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submitQuestion() })
                        )
                        val canSend = questionText.isNotBlank() && !uiState.memoryStatus.isAnswering
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (canSend) SecondMindColors.HeroCoral else SecondMindColors.CardElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = submitQuestion,
                                enabled = canSend
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send question",
                                    tint = if (canSend) Color.White else SecondMindColors.MutedText
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryHubTab(
    uiState: PrimaryUiState,
    selectedSubTab: Int,
    onSelectSubTab: (Int) -> Unit,
    vaultListState: LazyListState = rememberLazyListState(),
    onMemorySearchChange: (String) -> Unit,
    onMemoryCategoryChange: (MemoryCategory?) -> Unit,
    onMemoryVaultFilterChange: (MemoryVaultFilter) -> Unit,
    onEntityFilterChange: (String?) -> Unit,
    onExtractPending: () -> Unit,
    onRetryFailedExtraction: () -> Unit,
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
    onShowUndoSnackbar: (String, () -> Unit) -> Unit,
    onInspectSourceTranscript: (String) -> Unit,
    onOpenAudioStorage: () -> Unit
) {
    var showCaptureDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryItemEntity?>(null) }
    val subTabChipColors = galleryFilterChipColors(onWhiteCard = false, useCoralSelection = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedSubTab == 0,
                onClick = { onSelectSubTab(0) },
                colors = subTabChipColors,
                border = null,
                shape = RoundedCornerShape(50),
                label = { Text("Vault (${uiState.memories.size})") }
            )
            FilterChip(
                selected = selectedSubTab == 1,
                onClick = { onSelectSubTab(1) },
                colors = subTabChipColors,
                border = null,
                shape = RoundedCornerShape(50),
                label = { Text("Insights") }
            )
            FilterChip(
                selected = selectedSubTab == 2,
                onClick = { onSelectSubTab(2) },
                colors = subTabChipColors,
                border = null,
                shape = RoundedCornerShape(50),
                label = { Text("Digests (${uiState.summaries.size})") }
            )
            FilterChip(
                selected = selectedSubTab == 3,
                onClick = { onSelectSubTab(3) },
                colors = subTabChipColors,
                border = null,
                shape = RoundedCornerShape(50),
                label = { Text("Tasks (${uiState.insights.openTasksCount})") }
            )
        }

        uiState.memoryStatus.lastError?.let { err ->
            Text(err, color = SecondMindColors.CoralAlert, style = MaterialTheme.typography.bodySmall)
        }

        when (selectedSubTab) {
            0 -> MemoryVaultSubTab(
                uiState = uiState,
                listState = vaultListState,
                onSearchChange = onMemorySearchChange,
                onCategoryChange = onMemoryCategoryChange,
                onVaultFilterChange = onMemoryVaultFilterChange,
                onEntityFilterChange = onEntityFilterChange,
                onExtractPending = onExtractPending,
                onRetryFailedExtraction = onRetryFailedExtraction,
                onTogglePin = onTogglePinMemory,
                onEditClick = { editingMemory = it },
                onArchiveMemory = { memId ->
                    onArchiveMemory(memId)
                    onShowUndoSnackbar("Memory archived") { onRestoreMemory(memId) }
                },
                onRestoreMemory = onRestoreMemory,
                onDeletePermanently = onDeleteMemoryPermanently,
                onPromoteToTask = onPromoteMemoryToTask,
                onInspectSourceTranscript = onInspectSourceTranscript,
                onOpenCaptureDialog = { showCaptureDialog = true }
            )
            1 -> InsightsSubTab(
                uiState = uiState,
                onGenerateBrief = onGenerateProductivityBrief,
                onSelectCategoryJump = { cat ->
                    onMemoryVaultFilterChange(MemoryVaultFilter.ACTIVE)
                    onMemoryCategoryChange(cat)
                    onSelectSubTab(0)
                },
                onSelectEntityJump = { entityName ->
                    onMemoryVaultFilterChange(MemoryVaultFilter.ACTIVE)
                    onEntityFilterChange(entityName)
                    onSelectSubTab(0)
                },
                onOpenAudioStorage = onOpenAudioStorage
            )
            2 -> SummariesSubTab(
                uiState = uiState,
                onGenerateSummary = onGenerateSummary
            )
            3 -> TasksAndRemindersSubTab(
                uiState = uiState,
                onUpdateTaskStatus = onUpdateTaskStatus,
                onUpdateTaskPriority = onUpdateTaskPriority,
                onCreateManualTask = onCreateManualTask,
                onEditTask = onEditTask,
                onShowUndoSnackbar = onShowUndoSnackbar,
                onInspectSourceTranscript = onInspectSourceTranscript
            )
        }
    }

    if (showCaptureDialog) {
        QuickCaptureThoughtDialog(
            onDismiss = { showCaptureDialog = false },
            onSave = { title, content, cat, pin, createTask ->
                showCaptureDialog = false
                onCaptureManualMemory(title, content, cat, pin, createTask)
            }
        )
    }

    editingMemory?.let { mem ->
        EditMemoryDialog(
            memory = mem,
            onDismiss = { editingMemory = null },
            onSave = { title, content, cat ->
                editingMemory = null
                onEditMemory(mem.memoryId, title, content, cat)
            }
        )
    }
}

@Composable
private fun MemoryVaultSubTab(
    uiState: PrimaryUiState,
    listState: LazyListState,
    onSearchChange: (String) -> Unit,
    onCategoryChange: (MemoryCategory?) -> Unit,
    onVaultFilterChange: (MemoryVaultFilter) -> Unit,
    onEntityFilterChange: (String?) -> Unit,
    onExtractPending: () -> Unit,
    onRetryFailedExtraction: () -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onEditClick: (MemoryItemEntity) -> Unit,
    onArchiveMemory: (String) -> Unit,
    onRestoreMemory: (String) -> Unit,
    onDeletePermanently: (String) -> Unit,
    onPromoteToTask: (MemoryItemEntity) -> Unit,
    onInspectSourceTranscript: (String) -> Unit,
    onOpenCaptureDialog: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val focusManager = LocalFocusManager.current
    var pendingDeleteMemory by remember { mutableStateOf<MemoryItemEntity?>(null) }
    val chipColors = galleryFilterChipColors(onWhiteCard = false)

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = uiState.memorySearchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                colors = galleryTextFieldColors(),
                placeholder = { Text("Search memories & topics...", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.memorySearchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )

            // Vault Scope: Active / Pinned / Archived
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MemoryVaultFilter.entries.forEach { vf ->
                    val count = when (vf) {
                        MemoryVaultFilter.ACTIVE -> uiState.memories.size
                        MemoryVaultFilter.PINNED -> uiState.insights.pinnedCount
                        MemoryVaultFilter.ARCHIVED -> uiState.archivedMemories.size
                    }
                    FilterChip(
                        selected = uiState.memoryVaultFilter == vf,
                        onClick = { onVaultFilterChange(vf) },
                        colors = chipColors,
                        border = null,
                        shape = RoundedCornerShape(50),
                        label = { Text("${vf.label} ($count)", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    )
                }
            }

            // Category chips
            Row(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = uiState.selectedMemoryCategory == null,
                    onClick = { onCategoryChange(null) },
                    colors = chipColors,
                    border = null,
                    shape = RoundedCornerShape(50),
                    label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                )
                MemoryCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = uiState.selectedMemoryCategory == cat,
                        onClick = { onCategoryChange(cat) },
                        colors = chipColors,
                        border = null,
                        shape = RoundedCornerShape(50),
                        label = { Text(cat.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    )
                }
            }

            uiState.selectedEntityFilter?.let { ent ->
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        label = "Topic: $ent (tap to clear)",
                        dotColor = SecondMindColors.VioletAi,
                        containerColor = SecondMindColors.VioletContainer,
                        onClick = { onEntityFilterChange(null) }
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SoftPillButton(
                    text = "Extract Pending",
                    compact = true,
                    enabled = !uiState.memoryStatus.isExtracting,
                    containerColor = SecondMindColors.CardSurface,
                    onClick = onExtractPending
                )
                SoftPillButton(
                    text = "Retry Failed",
                    compact = true,
                    containerColor = SecondMindColors.CardElevated,
                    onClick = onRetryFailedExtraction
                )
            }
            if (uiState.memoryStatus.isExtracting) {
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

        if (uiState.topEntities.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    uiState.topEntities.take(10).forEach { entity ->
                        FilterChip(
                            selected = uiState.selectedEntityFilter == entity.name,
                            onClick = { onEntityFilterChange(entity.name) },
                            colors = chipColors,
                            border = null,
                            shape = RoundedCornerShape(50),
                            label = { Text("${entity.name} (${entity.mentionCount})", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        if (uiState.filteredMemories.isEmpty()) {
            item {
                val hasActiveFilter = uiState.memorySearchQuery.isNotBlank() ||
                    uiState.selectedMemoryCategory != null ||
                    uiState.selectedEntityFilter != null
                MindSurfaceCard {
                    Text(
                        text = when {
                            hasActiveFilter -> "No memories match the active search or filter."
                            uiState.memoryVaultFilter == MemoryVaultFilter.PINNED -> "No pinned memories yet. Tap the pin icon on any key decision, idea, or fact to keep it at the top of your 2nd Mind."
                            uiState.memoryVaultFilter == MemoryVaultFilter.ARCHIVED -> "No archived memories."
                            else -> "No memories yet. Sync speech from your Secondary Recorder or capture a quick note."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.SecondaryInk
                    )
                    if (hasActiveFilter) {
                        SoftPillButton(
                            text = "Clear Filters",
                            compact = true,
                            onClick = {
                                onSearchChange("")
                                onCategoryChange(null)
                                onEntityFilterChange(null)
                            }
                        )
                    } else if (uiState.memoryVaultFilter == MemoryVaultFilter.ACTIVE) {
                        SoftPillButton(
                            text = "Capture Thought",
                            icon = Icons.Default.Add,
                            compact = true,
                            containerColor = SecondMindColors.PrimaryInk,
                            contentColor = Color.White,
                            onClick = onOpenCaptureDialog
                        )
                    }
                }
            }
        } else {
            items(uiState.filteredMemories, key = { it.memoryId }) { mem ->
                val accent = categoryAccentColor(mem.category)
                MindSurfaceCard(
                    borderColor = if (mem.isPinned) SecondMindColors.StarGold else SecondMindColors.HairlineBorder
                ) {
                    if (mem.isPinned) {
                        StarEyebrowBadge(label = "Pinned in 2nd Mind")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StatusPill(
                                label = mem.category.name.replace('_', ' '),
                                dotColor = accent,
                                containerColor = SecondMindColors.CardElevated
                            )
                            Text(
                                formatter.format(Date(mem.sourceTimestampMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = SecondMindColors.MutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when (mem.certaintyLevel) {
                                    CertaintyLevel.EXPLICIT -> "Verified ${(mem.confidence * 100).toInt()}%"
                                    CertaintyLevel.INFERRED_MEDIUM -> "Inferred ${(mem.confidence * 100).toInt()}%"
                                    CertaintyLevel.UNCERTAIN -> "Uncertain ${(mem.confidence * 100).toInt()}%"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (mem.certaintyLevel == CertaintyLevel.UNCERTAIN) SecondMindColors.CoralAlert else SecondMindColors.MutedText,
                                maxLines = 1,
                                softWrap = false
                            )
                            if (!mem.isArchived) {
                                IconButton(
                                    onClick = { onTogglePin(mem.memoryId, mem.isPinned) },
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = if (mem.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                        contentDescription = if (mem.isPinned) "Unpin memory" else "Pin memory",
                                        tint = if (mem.isPinned) SecondMindColors.AmberHighlight else SecondMindColors.MutedText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        mem.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SecondMindColors.PrimaryInk
                    )
                    Text(
                        mem.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.SecondaryInk
                    )

                    if (mem.entitiesCsv.isNotBlank()) {
                        Text(
                            "Topics: ${mem.entitiesCsv}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondMindColors.CyanAccent
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!mem.isArchived) {
                            SoftPillButton(
                                text = "Edit",
                                icon = Icons.Default.Edit,
                                compact = true,
                                onClick = { onEditClick(mem) }
                            )
                            SoftPillButton(
                                text = "+Task",
                                icon = Icons.Default.AddTask,
                                compact = true,
                                onClick = { onPromoteToTask(mem) }
                            )
                            if (mem.sessionId != "manual_notes" && mem.sourceTranscriptId in uiState.spokenTranscriptIds) {
                                SoftPillButton(
                                    text = "Source",
                                    compact = true,
                                    containerColor = SecondMindColors.BlueSoftContainer,
                                    contentColor = SecondMindColors.CyanAccent,
                                    onClick = { onInspectSourceTranscript(mem.sourceTranscriptId) }
                                )
                            }
                            TextButton(onClick = { onArchiveMemory(mem.memoryId) }) {
                                Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Archive", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            SoftPillButton(
                                text = "Restore",
                                icon = Icons.Default.Restore,
                                compact = true,
                                onClick = { onRestoreMemory(mem.memoryId) }
                            )
                            SoftPillButton(
                                text = "Delete",
                                icon = Icons.Default.Delete,
                                compact = true,
                                containerColor = SecondMindColors.HeroCoralSoft,
                                contentColor = SecondMindColors.CoralAlert,
                                onClick = { pendingDeleteMemory = mem }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDeleteMemory?.let { mem ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMemory = null },
            containerColor = SecondMindColors.CardSurface,
            title = { Text("Delete memory permanently?") },
            text = { Text("\"${mem.title.take(64)}\" will be removed permanently from your Sunbo 2nd Mind Vault.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteMemory = null
                    onDeletePermanently(mem.memoryId)
                }) { Text("Delete", color = SecondMindColors.CoralAlert) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMemory = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InsightsSubTab(
    uiState: PrimaryUiState,
    onGenerateBrief: () -> Unit,
    onSelectCategoryJump: (MemoryCategory) -> Unit,
    onSelectEntityJump: (String) -> Unit,
    onOpenAudioStorage: () -> Unit
) {
    val ins = uiState.insights
    val cardChipColors = galleryFilterChipColors(onWhiteCard = true)

    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    value = "${ins.captureStreakDays}d",
                    label = "Active Streak",
                    accent = SecondMindColors.HeroCoral,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    value = formatDurationFriendly(ins.totalSpokenSeconds),
                    label = "Total Speech",
                    accent = SecondMindColors.CyanAccent,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    value = "${(ins.taskCompletionRate * 100).toInt()}%",
                    label = "Tasks Done",
                    accent = SecondMindColors.EmeraldPrivacy,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Offline AI Productivity Brief Card
        item {
            MindSurfaceCard {
                StarEyebrowBadge(label = "On-device Gemma synthesis")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Offline AI Productivity Brief",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SecondMindColors.PrimaryInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    SoftPillButton(
                        text = if (uiState.aiProductivityBrief == null) "Generate" else "Refresh",
                        icon = Icons.Default.AutoAwesome,
                        compact = true,
                        enabled = !uiState.isGeneratingBrief,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = onGenerateBrief
                    )
                }
                if (uiState.isGeneratingBrief) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = SecondMindColors.HeroCoral,
                        trackColor = SecondMindColors.HeroCoralSoft
                    )
                }
                val briefText = uiState.aiProductivityBrief
                if (briefText != null) {
                    SelectionContainer {
                        MarkdownText(
                            text = briefText,
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.SecondaryInk
                        )
                    }
                } else {
                    Text(
                        "Synthesize your recent memories, open commitments, and focus themes locally on-device with zero cloud calls.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.SecondaryInk
                    )
                }
            }
        }

        item {
            MindSurfaceCard {
                WeeklyActivityBarChart(buckets = ins.weeklyBuckets)
            }
        }

        if (ins.topCategories.isNotEmpty()) {
            item {
                MindSurfaceCard {
                    Text(
                        "Knowledge Distribution",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SecondMindColors.PrimaryInk
                    )
                    Text(
                        "Tap any category to filter your Vault",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.MutedText
                    )
                    CategoryBreakdownBars(
                        shares = ins.topCategories,
                        onSelectCategory = onSelectCategoryJump
                    )
                }
            }
        }

        if (ins.languageBreakdown.isNotEmpty() || ins.cleanTopEntities.isNotEmpty()) {
            item {
                MindSurfaceCard {
                    if (ins.languageBreakdown.isNotEmpty()) {
                        Text(
                            "Language Mix",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SecondMindColors.PrimaryInk
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ins.languageBreakdown.forEach { lang ->
                                StatusPill(
                                    label = "${lang.label}: ${lang.count} (${(lang.ratio * 100).toInt()}%)",
                                    dotColor = when (lang.code) {
                                        "hi" -> SecondMindColors.HeroCoral
                                        "hi-en" -> SecondMindColors.VioletAi
                                        else -> SecondMindColors.CyanAccent
                                    },
                                    containerColor = SecondMindColors.CardElevated
                                )
                            }
                        }
                    }

                    if (ins.cleanTopEntities.isNotEmpty()) {
                        Text(
                            "Recurring People & Topics",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SecondMindColors.PrimaryInk,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ins.cleanTopEntities.take(12).forEach { ent ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onSelectEntityJump(ent.name) },
                                    colors = cardChipColors,
                                    border = null,
                                    shape = RoundedCornerShape(50),
                                    label = { Text("${ent.name} • ${ent.mentionCount}", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            MindSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Encrypted Audio Footprint",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SecondMindColors.PrimaryInk
                        )
                        Text(
                            "${uiState.activeAudioChunkCount} chunk(s) • ${formatStorageBytes(uiState.activeAudioBytes)} • Policy: ${uiState.appSettings.audioRetentionPolicy.shortLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.SecondaryInk
                        )
                    }
                    SoftPillButton(text = "Manage", compact = true, onClick = onOpenAudioStorage)
                }
            }
        }
    }
}

@Composable
private fun SummariesSubTab(
    uiState: PrimaryUiState,
    onGenerateSummary: (SummaryType) -> Unit
) {
    val formatter = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MindSurfaceCard {
                Text(
                    "Generate Offline Digest",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondMindColors.PrimaryInk
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoftPillButton(
                        text = "Daily Digest",
                        compact = true,
                        enabled = !uiState.memoryStatus.isSummarizing,
                        containerColor = SecondMindColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = { onGenerateSummary(SummaryType.DAILY) }
                    )
                    SoftPillButton(
                        text = "Weekly Digest",
                        compact = true,
                        enabled = !uiState.memoryStatus.isSummarizing,
                        onClick = { onGenerateSummary(SummaryType.WEEKLY) }
                    )
                    SoftPillButton(
                        text = "Conversation",
                        compact = true,
                        enabled = !uiState.memoryStatus.isSummarizing,
                        onClick = { onGenerateSummary(SummaryType.CONVERSATION) }
                    )
                    SoftPillButton(
                        text = "Meeting",
                        compact = true,
                        enabled = !uiState.memoryStatus.isSummarizing,
                        onClick = { onGenerateSummary(SummaryType.MEETING) }
                    )
                }
                if (uiState.memoryStatus.isSummarizing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = SecondMindColors.HeroCoral,
                        trackColor = SecondMindColors.HeroCoralSoft
                    )
                }
            }
        }

        if (uiState.summaries.isEmpty()) {
            item {
                MindSurfaceCard {
                    Text(
                        "Tap Daily, Weekly, Conversation, or Meeting above to generate a source-grounded digest from your transcripts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.SecondaryInk
                    )
                }
            }
        } else {
            items(uiState.summaries, key = { it.summaryId }) { summary ->
                MindSurfaceCard {
                    Text(
                        "${formatHumanEnum(summary.summaryType.name)} • ${formatter.format(Date(summary.createdAtMs))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.CyanAccent
                    )
                    Text(
                        summary.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SecondMindColors.PrimaryInk
                    )
                    SelectionContainer {
                        MarkdownText(
                            text = summary.markdownBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.SecondaryInk
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksAndRemindersSubTab(
    uiState: PrimaryUiState,
    onUpdateTaskStatus: (String, TaskReminderStatus) -> Unit,
    onUpdateTaskPriority: (String, Int) -> Unit,
    onCreateManualTask: (String, String, String?, Int) -> Unit,
    onEditTask: (TaskReminderEntity, String, String, String?, Int) -> Unit,
    onShowUndoSnackbar: (String, () -> Unit) -> Unit,
    onInspectSourceTranscript: (String) -> Unit
) {
    var showCompleted by rememberSaveable { mutableStateOf(true) }
    var showNewTaskDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TaskReminderEntity?>(null) }
    val chipColors = galleryFilterChipColors(onWhiteCard = false)

    val visibleTasks = remember(uiState.tasks, showCompleted) {
        if (showCompleted) uiState.tasks else uiState.tasks.filter { it.status == TaskReminderStatus.OPEN }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${uiState.insights.openTasksCount} open • ${uiState.insights.completedTasksCount} done",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = SecondMindColors.MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                FilterChip(
                    selected = !showCompleted,
                    onClick = { showCompleted = !showCompleted },
                    colors = chipColors,
                    border = null,
                    shape = RoundedCornerShape(50),
                    label = { Text(if (showCompleted) "Hide Done" else "Show All", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                )
                SoftPillButton(
                    text = "Task",
                    icon = Icons.Default.Add,
                    compact = true,
                    containerColor = SecondMindColors.PrimaryInk,
                    contentColor = Color.White,
                    onClick = { showNewTaskDialog = true }
                )
            }
        }

        if (visibleTasks.isEmpty()) {
            item {
                MindSurfaceCard {
                    Text(
                        "No tasks match this view. Speak commitments, promote any Vault memory with +Task, or tap + Task above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.SecondaryInk
                    )
                }
            }
        } else {
            items(visibleTasks, key = { it.taskId }) { task ->
                val isDone = task.status == TaskReminderStatus.DONE
                val isDismissed = task.status == TaskReminderStatus.DISMISSED
                val priorityColor = when (task.priority) {
                    2 -> SecondMindColors.CoralAlert
                    0 -> SecondMindColors.MutedText
                    else -> SecondMindColors.AmberHighlight
                }
                val priorityBg = when (task.priority) {
                    2 -> SecondMindColors.HeroCoralSoft
                    0 -> SecondMindColors.CardElevated
                    else -> SecondMindColors.AmberContainer
                }
                val priorityLabel = when (task.priority) {
                    2 -> "High"
                    0 -> "Low"
                    else -> "Normal"
                }

                MindSurfaceCard(
                    borderColor = if (!isDone && !isDismissed && task.priority == 2) SecondMindColors.CoralAlert.copy(alpha = 0.35f) else SecondMindColors.HairlineBorder
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Checkbox(
                            checked = isDone,
                            onCheckedChange = { checked ->
                                val prev = task.status
                                val next = if (checked) TaskReminderStatus.DONE else TaskReminderStatus.OPEN
                                onUpdateTaskStatus(task.taskId, next)
                                if (checked) {
                                    onShowUndoSnackbar("Task marked complete") { onUpdateTaskStatus(task.taskId, prev) }
                                }
                            }
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = task.title,
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = if (isDone || isDismissed) TextDecoration.LineThrough else null,
                                    color = if (isDismissed) SecondMindColors.MutedText else SecondMindColors.PrimaryInk,
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (isDismissed) SecondMindColors.CardElevated else priorityBg
                                ) {
                                    Text(
                                        text = if (isDismissed) "Dismissed" else priorityLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isDismissed) SecondMindColors.MutedText else priorityColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            if (task.notes.isNotBlank()) {
                                Text(task.notes, style = MaterialTheme.typography.bodySmall, color = SecondMindColors.SecondaryInk)
                            }
                            task.dueLabel?.let { due ->
                                Text(
                                    "Due: $due ${if (task.notificationScheduled) "• Reminder Scheduled" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SecondMindColors.CyanAccent
                                )
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SoftPillButton(
                                    text = "Edit",
                                    icon = Icons.Default.Edit,
                                    compact = true,
                                    onClick = { editingTask = task }
                                )
                                SoftPillButton(
                                    text = "Priority",
                                    icon = Icons.Default.Flag,
                                    compact = true,
                                    onClick = { onUpdateTaskPriority(task.taskId, (task.priority + 1) % 3) }
                                )
                                if (task.sourceTranscriptId in uiState.spokenTranscriptIds) {
                                    SoftPillButton(
                                        text = "Source",
                                        compact = true,
                                        containerColor = SecondMindColors.BlueSoftContainer,
                                        contentColor = SecondMindColors.CyanAccent,
                                        onClick = { onInspectSourceTranscript(task.sourceTranscriptId) }
                                    )
                                }
                                if (!isDismissed) {
                                    TextButton(onClick = {
                                        val prev = task.status
                                        onUpdateTaskStatus(task.taskId, TaskReminderStatus.DISMISSED)
                                        onShowUndoSnackbar("Task dismissed") { onUpdateTaskStatus(task.taskId, prev) }
                                    }) {
                                        Text("Dismiss", style = MaterialTheme.typography.labelSmall, color = SecondMindColors.MutedText)
                                    }
                                } else {
                                    SoftPillButton(
                                        text = "Reopen",
                                        icon = Icons.Default.Restore,
                                        compact = true,
                                        onClick = { onUpdateTaskStatus(task.taskId, TaskReminderStatus.OPEN) }
                                    )
                                }
                            }
                        }
                        if (isDone) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Completed", tint = SecondMindColors.EmeraldPrivacy)
                        }
                    }
                }
            }
        }
    }

    if (showNewTaskDialog) {
        TaskEditorDialog(
            existingTask = null,
            onDismiss = { showNewTaskDialog = false },
            onSave = { title, notes, due, prio ->
                showNewTaskDialog = false
                onCreateManualTask(title, notes, due, prio)
            }
        )
    }

    editingTask?.let { task ->
        TaskEditorDialog(
            existingTask = task,
            onDismiss = { editingTask = null },
            onSave = { title, notes, due, prio ->
                editingTask = null
                onEditTask(task, title, notes, due, prio)
            }
        )
    }
}
