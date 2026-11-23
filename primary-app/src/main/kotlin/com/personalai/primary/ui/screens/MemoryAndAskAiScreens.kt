package com.personalai.primary.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.personalai.primary.data.db.CertaintyLevel
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.SummaryType
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
    MindSurfaceCard(
        containerColor = if (isConnected) SecondMindColors.EmeraldContainer else SecondMindColors.VioletContainer,
        borderColor = if (isConnected) SecondMindColors.EmeraldPrivacy.copy(alpha = 0.4f) else SecondMindColors.VioletAi.copy(alpha = 0.4f)
    ) {
        Text(
            "Universal Offline AI Provider (com.uoai.provider)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        when (connection) {
            null -> Text("Checking local UOAI loopback (127.0.0.1:8765)...", style = MaterialTheme.typography.bodySmall)
            UoaiConnectionState.ProviderNotInstalled -> {
                Text(
                    "Universal Offline AI Provider (com.uoai.provider) is not installed. Install it for 100% offline Gemma 3/4 inference, or use the offline rule engine fallback.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onRefresh) { Text("Re-check") }
            }
            is UoaiConnectionState.NeedsRegistration -> {
                Text(
                    "One-time OS-verified approval required to connect to your offline Gemma 3/4 provider.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onLaunchRegistration(connection.registrationIntent) }) {
                        Text("Approve UOAI Access")
                    }
                    OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                }
            }
            is UoaiConnectionState.ProviderNotRunning -> {
                Text(
                    "UOAI Provider service is stopped. Open the provider app and start the local API server.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenProvider(connection.launchProviderIntent) }) {
                        Text("Open UOAI Provider")
                    }
                    OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                }
            }
            is UoaiConnectionState.ModelNotReady -> {
                Text(
                    "UOAI model is not loaded yet (state: ${connection.modelState}).${connection.modelError?.let { " $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenProvider(connection.launchProviderIntent) }) {
                        Text("Open Provider & Load Model")
                    }
                    OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                }
            }
            is UoaiConnectionState.Connected -> {
                val modelLabel = connection.activeModel?.name?.ifBlank { connection.activeModel.id } ?: "auto"
                Text(
                    "Connected (100% Offline) • Model: $modelLabel • Context: ${connection.capabilities.contextWindowTokens} tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.EmeraldPrivacy,
                    fontWeight = FontWeight.Medium
                )
                if (!compact) {
                    Text(
                        "Capabilities: ${connection.capabilities.features.joinToString(", ").ifBlank { "chat" }} • Max Output: ${connection.capabilities.maxTokensLimit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.MutedText
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                        TextButton(onClick = onClearCredentials) { Text("Clear Token") }
                    }
                }
            }
            is UoaiConnectionState.Error -> {
                Text(
                    "UOAI Error${connection.errorCode?.let { " ($it)" } ?: ""}: ${connection.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRefresh) { Text("Retry") }
                    if (connection.launchProviderIntent != null) {
                        OutlinedButton(onClick = { onOpenProvider(connection.launchProviderIntent) }) {
                            Text("Open Provider")
                        }
                    }
                }
            }
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun AskAiTab(
    uiState: PrimaryUiState,
    onAskQuestion: (String) -> Unit,
    onStopAskAi: () -> Unit,
    onLaunchUoaiRegistration: (Intent) -> Unit,
    onOpenUoaiProvider: (Intent?) -> Unit,
    onRefreshUoaiConnection: () -> Unit,
    onClearUoaiCredentials: () -> Unit,
    onInspectSourceTranscript: (String) -> Unit
) {
    var questionText by rememberSaveable { mutableStateOf("") }
    val formatter = remember { SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()) }

    val quickPrompts = remember {
        listOf(
            "What did I discuss today?",
            "What did I promise to do?",
            "What deadlines are coming up?",
            "Key decisions & ideas this week",
            "Summarize my conversations this week"
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = SecondMindColors.VioletAi)
                    Column {
                        Text("Ask 2nd Mind", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "100% offline • Strictly cited to your spoken & captured memories",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondMindColors.MutedText
                        )
                    }
                }
                val conn = uiState.uoaiConnection
                if (conn is UoaiConnectionState.Connected) {
                    StatusPill(
                        label = conn.activeModel?.name?.take(14) ?: "Gemma",
                        dotColor = SecondMindColors.EmeraldPrivacy,
                        containerColor = SecondMindColors.EmeraldContainer
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = questionText,
                    onValueChange = { questionText = it },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Ask about talks, promises, ideas, people...") },
                    maxLines = 3
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (questionText.isNotBlank() && !uiState.memoryStatus.isAnswering) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        SecondMindColors.CardElevated
                    }
                ) {
                    IconButton(
                        onClick = {
                            if (questionText.isNotBlank()) {
                                onAskQuestion(questionText)
                                questionText = ""
                            }
                        },
                        enabled = questionText.isNotBlank() && !uiState.memoryStatus.isAnswering
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (questionText.isNotBlank() && !uiState.memoryStatus.isAnswering) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                SecondMindColors.MutedText
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickPrompts.forEach { prompt ->
                    FilterChip(
                        selected = false,
                        onClick = { onAskQuestion(prompt) },
                        label = { Text(prompt, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (uiState.memoryStatus.isAnswering) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f),
                        color = SecondMindColors.VioletAi
                    )
                    OutlinedButton(onClick = onStopAskAi) { Text("Stop") }
                }
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

        if (uiState.streamingAskText.isNotBlank()) {
            item {
                MindSurfaceCard(
                    containerColor = SecondMindColors.VioletContainer,
                    borderColor = SecondMindColors.VioletAi.copy(alpha = 0.5f)
                ) {
                    Text(
                        "Streaming offline response (Gemma 3/4)...",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.VioletAi,
                        fontWeight = FontWeight.Bold
                    )
                    Text(uiState.streamingAskText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        uiState.latestAskAiAnswer?.let { latest ->
            item {
                MindSurfaceCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    borderColor = SecondMindColors.CyanAccent.copy(alpha = 0.45f)
                ) {
                    Text("Q: ${latest.question}", fontWeight = FontWeight.Bold, color = SecondMindColors.CyanAccent)
                    SelectionContainer {
                        Text(latest.answer, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Provider: ${latest.providerUsed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.MutedText
                    )

                    if (latest.citedSources.isNotEmpty()) {
                        Text(
                            "Grounded Sources (tap to inspect original transcript):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        latest.citedSources.forEach { src ->
                            OutlinedButton(
                                onClick = { onInspectSourceTranscript(src.transcriptId) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "[${src.citationTag} • ${src.formattedTime}] ${src.text.take(72)}${if (src.text.length > 72) "…" else ""}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }

        if (uiState.askAiHistory.isNotEmpty()) {
            item {
                Text(
                    "Recent 2nd Mind Q&A",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SecondMindColors.MutedText
                )
            }
            items(uiState.askAiHistory, key = { it.queryId }) { item ->
                MindSurfaceCard {
                    Text(
                        "${formatter.format(Date(item.createdAtMs))} • ${item.providerUsed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.CyanAccent
                    )
                    Text("Q: ${item.question}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(item.answer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun MemoryHubTab(
    uiState: PrimaryUiState,
    initialSubTab: Int = 0,
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
    onInspectSourceTranscript: (String) -> Unit,
    onOpenAudioStorage: () -> Unit
) {
    var subTab by rememberSaveable(initialSubTab) { mutableIntStateOf(initialSubTab) } // 0=Vault, 1=Insights, 2=Digests, 3=Tasks
    var showCaptureDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryItemEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("2nd Mind", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(
                onClick = { showCaptureDialog = true },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Capture Thought")
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = subTab == 0, onClick = { subTab = 0 }, label = { Text("Vault (${uiState.memories.size})") })
            FilterChip(selected = subTab == 1, onClick = { subTab = 1 }, label = { Text("Insights") })
            FilterChip(selected = subTab == 2, onClick = { subTab = 2 }, label = { Text("Digests (${uiState.summaries.size})") })
            FilterChip(
                selected = subTab == 3,
                onClick = { subTab = 3 },
                label = { Text("Tasks (${uiState.insights.openTasksCount})") }
            )
        }

        uiState.memoryStatus.lastError?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        when (subTab) {
            0 -> MemoryVaultSubTab(
                uiState = uiState,
                onSearchChange = onMemorySearchChange,
                onCategoryChange = onMemoryCategoryChange,
                onVaultFilterChange = onMemoryVaultFilterChange,
                onEntityFilterChange = onEntityFilterChange,
                onExtractPending = onExtractPending,
                onRetryFailedExtraction = onRetryFailedExtraction,
                onTogglePin = onTogglePinMemory,
                onEditClick = { editingMemory = it },
                onArchiveMemory = onArchiveMemory,
                onRestoreMemory = onRestoreMemory,
                onDeletePermanently = onDeleteMemoryPermanently,
                onPromoteToTask = onPromoteMemoryToTask,
                onInspectSourceTranscript = onInspectSourceTranscript
            )
            1 -> InsightsSubTab(
                uiState = uiState,
                onGenerateBrief = onGenerateProductivityBrief,
                onSelectCategoryJump = { cat ->
                    onMemoryVaultFilterChange(MemoryVaultFilter.ACTIVE)
                    onMemoryCategoryChange(cat)
                    subTab = 0
                },
                onSelectEntityJump = { entityName ->
                    onMemoryVaultFilterChange(MemoryVaultFilter.ACTIVE)
                    onEntityFilterChange(entityName)
                    subTab = 0
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
    onInspectSourceTranscript: (String) -> Unit
) {
    val formatter = remember { SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(
                value = uiState.memorySearchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("Search your 2nd Mind (hybrid semantic + keyword)...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.memorySearchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true
            )

            // Vault Scope: Active / Pinned / Archived
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        label = { Text("${vf.label} ($count)", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // Category chips
            Row(
                modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = uiState.selectedMemoryCategory == null,
                    onClick = { onCategoryChange(null) },
                    label = { Text("All") }
                )
                MemoryCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = uiState.selectedMemoryCategory == cat,
                        onClick = { onCategoryChange(cat) },
                        label = { Text(cat.name, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // Active Entity filter badge if tapped
            uiState.selectedEntityFilter?.let { ent ->
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        label = "Topic: $ent (tap to clear)",
                        dotColor = SecondMindColors.VioletAi,
                        onClick = { onEntityFilterChange(null) }
                    )
                }
            }

            Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExtractPending, enabled = !uiState.memoryStatus.isExtracting) {
                    Text("Extract Pending")
                }
                TextButton(onClick = onRetryFailedExtraction) { Text("Retry Failed") }
            }
            if (uiState.memoryStatus.isExtracting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
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
                            label = { Text("${entity.name} (${entity.mentionCount})", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        if (uiState.filteredMemories.isEmpty()) {
            item {
                MindSurfaceCard {
                    Text(
                        text = when (uiState.memoryVaultFilter) {
                            MemoryVaultFilter.PINNED -> "No pinned memories yet. Tap the pin icon on any key decision, idea, or fact to keep it at the top of your 2nd Mind."
                            MemoryVaultFilter.ARCHIVED -> "No archived memories."
                            MemoryVaultFilter.ACTIVE -> "No memories match yet. Sync speech from Device B or tap Capture Thought above."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.MutedText
                    )
                }
            }
        } else {
            items(uiState.filteredMemories, key = { it.memoryId }) { mem ->
                val accent = categoryAccentColor(mem.category)
                MindSurfaceCard(
                    borderColor = if (mem.isPinned) SecondMindColors.AmberHighlight.copy(alpha = 0.55f) else SecondMindColors.HairlineBorder
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill(
                                label = mem.category.name.replace('_', ' '),
                                dotColor = accent
                            )
                            Text(
                                formatter.format(Date(mem.sourceTimestampMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = SecondMindColors.MutedText
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
                                color = if (mem.certaintyLevel == CertaintyLevel.UNCERTAIN) SecondMindColors.CoralAlert else SecondMindColors.MutedText
                            )
                            if (!mem.isArchived) {
                                IconButton(
                                    onClick = { onTogglePin(mem.memoryId, mem.isPinned) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (mem.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                        contentDescription = if (mem.isPinned) "Unpin" else "Pin",
                                        tint = if (mem.isPinned) SecondMindColors.AmberHighlight else SecondMindColors.MutedText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text(mem.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(mem.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!mem.isArchived) {
                            TextButton(onClick = { onEditClick(mem) }) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                Text(" Edit", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onPromoteToTask(mem) }) {
                                Icon(Icons.Default.AddTask, contentDescription = null, modifier = Modifier.size(15.dp))
                                Text(" +Task", style = MaterialTheme.typography.labelSmall)
                            }
                            if (mem.sessionId != "manual_notes") {
                                TextButton(onClick = { onInspectSourceTranscript(mem.sourceTranscriptId) }) {
                                    Text("Source", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            TextButton(onClick = { onArchiveMemory(mem.memoryId) }) {
                                Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(15.dp))
                                Text(" Archive", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            TextButton(onClick = { onRestoreMemory(mem.memoryId) }) {
                                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                                Text(" Restore", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onDeletePermanently(mem.memoryId) }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = SecondMindColors.CoralAlert, modifier = Modifier.size(15.dp))
                                Text(" Delete", style = MaterialTheme.typography.labelSmall, color = SecondMindColors.CoralAlert)
                            }
                        }
                    }
                }
            }
        }
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

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Executive KPI Strip
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    value = "${ins.captureStreakDays}d",
                    label = "Active Streak",
                    accent = SecondMindColors.AmberHighlight,
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

        // Gemma 4 AI Productivity Brief Card
        item {
            MindSurfaceCard(
                containerColor = SecondMindColors.VioletContainer,
                borderColor = SecondMindColors.VioletAi.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = SecondMindColors.VioletAi, modifier = Modifier.size(18.dp))
                        Text("Offline AI Productivity Brief", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onGenerateBrief,
                        enabled = !uiState.isGeneratingBrief
                    ) {
                        Text(if (uiState.aiProductivityBrief == null) "Generate" else "Refresh", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (uiState.isGeneratingBrief) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SecondMindColors.VioletAi)
                }
                val briefText = uiState.aiProductivityBrief
                if (briefText != null) {
                    SelectionContainer {
                        Text(briefText, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        "Synthesize your recent memories, open commitments, and focus themes locally using Gemma 4 E2B (zero cloud).",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.MutedText
                    )
                }
            }
        }

        // 7-Day Activity Bar Chart
        item {
            MindSurfaceCard {
                WeeklyActivityBarChart(buckets = ins.weeklyBuckets)
            }
        }

        // Category Distribution (Tappable -> filters Vault)
        if (ins.topCategories.isNotEmpty()) {
            item {
                MindSurfaceCard {
                    Text("Knowledge Distribution (tap to explore)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    CategoryBreakdownBars(
                        shares = ins.topCategories,
                        onSelectCategory = onSelectCategoryJump
                    )
                }
            }
        }

        // Language Mix & Top Focus Topics
        item {
            MindSurfaceCard {
                if (ins.languageBreakdown.isNotEmpty()) {
                    Text("Language Mix", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ins.languageBreakdown.forEach { lang ->
                            StatusPill(
                                label = "${lang.label}: ${lang.count} (${(lang.ratio * 100).toInt()}%)",
                                dotColor = when (lang.code) {
                                    "hi" -> SecondMindColors.AmberHighlight
                                    "hi-en" -> SecondMindColors.VioletAi
                                    else -> SecondMindColors.CyanAccent
                                }
                            )
                        }
                    }
                }

                if (ins.cleanTopEntities.isNotEmpty()) {
                    Text(
                        "Recurring People & Topics (tap to filter Vault)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
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
                                label = { Text("${ent.name} • ${ent.mentionCount}", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }

        // Audio Storage Health Shortcut
        item {
            MindSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Encrypted Audio Footprint", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${uiState.activeAudioChunkCount} audio chunk(s) • ${formatStorageBytes(uiState.activeAudioBytes)} • Auto-delete: ${uiState.appSettings.audioRetentionPolicy.shortLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondMindColors.MutedText
                        )
                    }
                    OutlinedButton(onClick = onOpenAudioStorage) { Text("Manage") }
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
    val formatter = remember { SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            MindSurfaceCard {
                Text("Generate Offline Digest (Gemma 3/4)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { onGenerateSummary(SummaryType.DAILY) }, enabled = !uiState.memoryStatus.isSummarizing) {
                        Text("Daily Digest")
                    }
                    Button(onClick = { onGenerateSummary(SummaryType.WEEKLY) }, enabled = !uiState.memoryStatus.isSummarizing) {
                        Text("Weekly Digest")
                    }
                    OutlinedButton(onClick = { onGenerateSummary(SummaryType.CONVERSATION) }, enabled = !uiState.memoryStatus.isSummarizing) {
                        Text("Conversation")
                    }
                    OutlinedButton(onClick = { onGenerateSummary(SummaryType.MEETING) }, enabled = !uiState.memoryStatus.isSummarizing) {
                        Text("Meeting")
                    }
                }
                if (uiState.memoryStatus.isSummarizing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SecondMindColors.VioletAi)
                }
            }
        }

        if (uiState.summaries.isEmpty()) {
            item {
                MindSurfaceCard {
                    Text(
                        "Tap Daily, Weekly, Conversation, or Meeting above to generate a source-grounded digest from your transcripts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.MutedText
                    )
                }
            }
        } else {
            items(uiState.summaries, key = { it.summaryId }) { summary ->
                MindSurfaceCard {
                    Text(
                        "${summary.summaryType.name} • Generated ${formatter.format(Date(summary.createdAtMs))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.CyanAccent
                    )
                    Text(summary.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    SelectionContainer {
                        Text(summary.markdownBody, style = MaterialTheme.typography.bodySmall)
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
    onInspectSourceTranscript: (String) -> Unit
) {
    var showCompleted by rememberSaveable { mutableStateOf(true) }
    val visibleTasks = remember(uiState.tasks, showCompleted) {
        if (showCompleted) uiState.tasks else uiState.tasks.filter { it.status == TaskReminderStatus.OPEN }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${uiState.insights.openTasksCount} open • ${uiState.insights.completedTasksCount} completed",
                    style = MaterialTheme.typography.labelMedium,
                    color = SecondMindColors.MutedText
                )
                FilterChip(
                    selected = !showCompleted,
                    onClick = { showCompleted = !showCompleted },
                    label = { Text(if (showCompleted) "Hide Done" else "Show All", style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        if (visibleTasks.isEmpty()) {
            item {
                MindSurfaceCard {
                    Text(
                        "No open tasks, promises, or follow-ups yet. Speak commitments or promote any Vault memory with +Task.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.MutedText
                    )
                }
            }
        } else {
            items(visibleTasks, key = { it.taskId }) { task ->
                val isDone = task.status == TaskReminderStatus.DONE
                val priorityColor = when (task.priority) {
                    2 -> SecondMindColors.CoralAlert
                    0 -> SecondMindColors.MutedText
                    else -> SecondMindColors.AmberHighlight
                }
                val priorityLabel = when (task.priority) {
                    2 -> "High"
                    0 -> "Low"
                    else -> "Normal"
                }

                MindSurfaceCard(
                    borderColor = if (!isDone && task.priority == 2) SecondMindColors.CoralAlert.copy(alpha = 0.45f) else SecondMindColors.HairlineBorder
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = isDone,
                            onCheckedChange = { checked ->
                                onUpdateTaskStatus(
                                    task.taskId,
                                    if (checked) TaskReminderStatus.DONE else TaskReminderStatus.OPEN
                                )
                            }
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = task.title,
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = if (isDone) TextDecoration.LineThrough else null,
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = priorityColor.copy(alpha = 0.16f),
                                    border = BorderStroke(1.dp, priorityColor.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = priorityLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = priorityColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(task.notes, style = MaterialTheme.typography.bodySmall, color = SecondMindColors.MutedText)
                            task.dueLabel?.let { due ->
                                Text(
                                    "Due: $due ${if (task.notificationScheduled) "• Reminder Scheduled" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SecondMindColors.CyanAccent
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onUpdateTaskPriority(task.taskId, (task.priority + 1) % 3) }) {
                                    Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Text(" Priority", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { onInspectSourceTranscript(task.sourceTranscriptId) }) {
                                    Text("Source", style = MaterialTheme.typography.labelSmall)
                                }
                                if (task.status != TaskReminderStatus.DISMISSED) {
                                    TextButton(onClick = { onUpdateTaskStatus(task.taskId, TaskReminderStatus.DISMISSED) }) {
                                        Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        if (isDone) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SecondMindColors.EmeraldPrivacy)
                        }
                    }
                }
            }
        }
    }
}
