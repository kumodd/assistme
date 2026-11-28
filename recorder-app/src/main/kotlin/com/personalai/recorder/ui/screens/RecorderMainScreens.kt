package com.personalai.recorder.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalai.protocol.AudioSourcePreset
import com.personalai.protocol.RecorderConfigPayload
import com.personalai.protocol.RecorderState
import com.personalai.recorder.domain.state.LiveMeterSnapshot
import com.personalai.recorder.ui.RecorderUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object RecorderGalleryColors {
    val Canvas = Color(0xFFFFF5F5)
    val TopBarSurface = Color(0xFFFFFFFF)
    val CardSurface = Color(0xFFFFFFFF)
    val PillSurface = Color(0xFFF0F4F9)
    val HairlineBorder = Color(0xFFECE2E2)
    val HeroCoral = Color(0xFFDE4A41)
    val HeroCoralSoft = Color(0xFFFDECEB)
    val LinkBlue = Color(0xFF32628D)
    val BlueSoft = Color(0xFFE8F1F8)
    val Emerald = Color(0xFF1E8E3E)
    val EmeraldSoft = Color(0xFFE6F4EA)
    val CoralAlert = Color(0xFFD93025)
    val PrimaryInk = Color(0xFF1F1F1F)
    val SecondaryInk = Color(0xFF444746)
    val MutedText = Color(0xFF747775)
}

@Composable
internal fun RecorderGalleryTopBar(
    title: String,
    centered: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = RecorderGalleryColors.TopBarSurface
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = RecorderGalleryColors.PrimaryInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(if (centered && trailing == null) Alignment.Center else Alignment.CenterStart)
                )
                if (trailing != null) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        content = trailing
                    )
                }
            }
            HorizontalDivider(thickness = 0.75.dp, color = RecorderGalleryColors.HairlineBorder)
        }
    }
}

@Composable
internal fun RecorderSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = RecorderGalleryColors.PrimaryInk,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
internal fun RecorderPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    containerColor: Color = RecorderGalleryColors.PillSurface,
    contentColor: Color = RecorderGalleryColors.PrimaryInk
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = RecorderGalleryColors.PillSurface.copy(alpha = 0.55f),
            disabledContentColor = RecorderGalleryColors.MutedText
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = if (compact) {
            PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        } else {
            PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        },
        modifier = modifier.heightIn(min = if (compact) 36.dp else 44.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 16.dp else 18.dp)
            )
            Spacer(Modifier.width(if (compact) 5.dp else 8.dp))
        }
        Text(
            text = text,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun RecorderSurfaceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = RecorderGalleryColors.CardSurface,
    borderColor: Color = RecorderGalleryColors.HairlineBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    val stroke = if (borderColor == RecorderGalleryColors.HairlineBorder) {
        BorderStroke(0.5.dp, borderColor.copy(alpha = 0.7f))
    } else {
        BorderStroke(1.dp, borderColor)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = stroke,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

private fun formatRecorderHumanLabel(raw: String): String =
    raw.lowercase().split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

private fun formatRecorderBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    if (bytes < 1024L) return "< 1 KB"
    if (bytes < 1024L * 1024L) return "${bytes / 1024L} KB"
    return "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

@Composable
fun RecorderOperationalShell(
    uiState: RecorderUiState,
    liveMeterFlow: StateFlow<LiveMeterSnapshot> = remember { MutableStateFlow(LiveMeterSnapshot()) },
    onArmAndStart: () -> Unit,
    onEmergencyStop: () -> Unit,
    onAcknowledgeInterruption: () -> Unit,
    onVerifySegment: (String) -> Unit,
    onClearIntegrityMsg: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onUpdateConfig: (RecorderConfigPayload) -> Unit,
    onSetAudioSource: (AudioSourcePreset) -> Unit,
    onSetPrivacyKillSwitch: (Boolean) -> Unit,
    onSetAutoDeleteSynced: (Boolean) -> Unit,
    onSetLocalOnlyPauseSync: (Boolean) -> Unit,
    onStartBleHost: () -> Unit,
    onStopBleHost: () -> Unit,
    onTogglePairingWindow: (Boolean) -> Unit,
    onApprovePairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onUnpair: () -> Unit,
    onWipeAll: () -> Unit,
    onReopenOnboarding: () -> Unit,
    onToggleMicTest: () -> Unit,
    onSwitchToPrimary: (() -> Unit)? = null
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var confirmStopRecording by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    val navItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = RecorderGalleryColors.HeroCoral,
        selectedTextColor = RecorderGalleryColors.PrimaryInk,
        indicatorColor = RecorderGalleryColors.HeroCoralSoft,
        unselectedIconColor = RecorderGalleryColors.MutedText,
        unselectedTextColor = RecorderGalleryColors.MutedText
    )

    Scaffold(
        containerColor = RecorderGalleryColors.Canvas,
        topBar = {
            val title = when (selectedTab) {
                0 -> "Secondary Recorder"
                1 -> "Bluetooth Pairing"
                2 -> "Device Health"
                3 -> "Diagnostics & VAD"
                else -> "Privacy & Role"
            }
            RecorderGalleryTopBar(title = title)
        },
        bottomBar = {
            Column {
                HorizontalDivider(thickness = 0.75.dp, color = RecorderGalleryColors.HairlineBorder)
                NavigationBar(
                    containerColor = RecorderGalleryColors.TopBarSurface,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                        label = { Text("Status") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                        label = { Text("Pairing") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null) },
                        label = { Text("Health") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.Build, contentDescription = null) },
                        label = { Text("Diag") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 4,
                        onClick = { selectedTab = 4 },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.PrivacyTip, contentDescription = null) },
                        label = { Text("Privacy") }
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
            ActiveRecordingStatusStrip(
                recorderState = uiState.snapshot.recorderState,
                onRequestStop = { confirmStopRecording = true }
            )

            when (selectedTab) {
                0 -> StatusAndSegmentsTab(
                    uiState = uiState,
                    liveMeterFlow = liveMeterFlow,
                    onArmAndStart = onArmAndStart,
                    onAcknowledgeInterruption = onAcknowledgeInterruption,
                    onVerifySegment = onVerifySegment,
                    onClearIntegrityMsg = onClearIntegrityMsg,
                    onRequestPermissions = onRequestPermissions,
                    onToggleMicTest = onToggleMicTest,
                    onOpenPairingTab = { selectedTab = 1 }
                )
                1 -> PairingManagementTab(
                    uiState = uiState,
                    onStartBleHost = onStartBleHost,
                    onStopBleHost = onStopBleHost,
                    onTogglePairingWindow = onTogglePairingWindow,
                    onApprovePairing = onApprovePairing,
                    onRejectPairing = onRejectPairing,
                    onUnpair = onUnpair
                )
                2 -> BatteryAndStorageTab(
                    uiState = uiState,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onOpenAppSettings = onOpenAppSettings
                )
                3 -> DiagnosticsAndTuningTab(
                    uiState = uiState,
                    onUpdateConfig = onUpdateConfig,
                    onSetAudioSource = onSetAudioSource
                )
                4 -> PrivacySettingsTab(
                    uiState = uiState,
                    onSetPrivacyKillSwitch = onSetPrivacyKillSwitch,
                    onSetAutoDeleteSynced = onSetAutoDeleteSynced,
                    onSetLocalOnlyPauseSync = onSetLocalOnlyPauseSync,
                    onWipeAll = onWipeAll,
                    onReopenOnboarding = onReopenOnboarding,
                    onSwitchToPrimary = onSwitchToPrimary
                )
            }
        }
    }

    if (confirmStopRecording) {
        AlertDialog(
            onDismissRequest = { confirmStopRecording = false },
            containerColor = RecorderGalleryColors.CardSurface,
            title = { Text("Stop active recording?") },
            text = { Text("The microphone service will stop and any in-progress speech segment will be flushed and encrypted safely.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStopRecording = false
                    onEmergencyStop()
                }) {
                    Text("Stop Recording", color = RecorderGalleryColors.CoralAlert, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStopRecording = false }) {
                    Text("Keep Recording")
                }
            }
        )
    }
}

@Composable
private fun ActiveRecordingStatusStrip(
    recorderState: RecorderState,
    onRequestStop: () -> Unit
) {
    if (recorderState.isArmedOrCapturing) {
        Surface(
            color = RecorderGalleryColors.HeroCoralSoft,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, RecorderGalleryColors.HeroCoral.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(RecorderGalleryColors.HeroCoral)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Recording Active • ${formatRecorderHumanLabel(recorderState.name)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6E1A14),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RecorderPillButton(
                    text = "Stop",
                    icon = Icons.Default.Stop,
                    compact = true,
                    containerColor = RecorderGalleryColors.HeroCoral,
                    contentColor = Color.White,
                    onClick = onRequestStop
                )
            }
        }
    }
}

@Composable
private fun StatusAndSegmentsTab(
    uiState: RecorderUiState,
    liveMeterFlow: StateFlow<LiveMeterSnapshot>,
    onArmAndStart: () -> Unit,
    onAcknowledgeInterruption: () -> Unit,
    onVerifySegment: (String) -> Unit,
    onClearIntegrityMsg: () -> Unit,
    onRequestPermissions: () -> Unit,
    onToggleMicTest: () -> Unit,
    onOpenPairingTab: () -> Unit
) {
    val snap = uiState.snapshot
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            RecorderSurfaceCard {
                Text("Recorder State", style = MaterialTheme.typography.labelMedium, color = RecorderGalleryColors.MutedText)
                Text(
                    text = formatRecorderHumanLabel(snap.recorderState.name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (snap.recorderState.isArmedOrCapturing) {
                        RecorderGalleryColors.HeroCoral
                    } else {
                        RecorderGalleryColors.PrimaryInk
                    }
                )
                Text(
                    "Input: ${snap.inputDeviceName} • Session Clips: ${snap.sessionSegmentsCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecorderGalleryColors.SecondaryInk
                )
                Text(
                    "Stored Total: ${uiState.totalSegmentCount} • Unsynced Queue: ${uiState.pendingSyncCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecorderGalleryColors.PrimaryInk
                )
                if (uiState.settings.config.localOnlyPauseSync) {
                    Text(
                        "Local Capture Only: Sync & Transcripts Paused",
                        color = RecorderGalleryColors.LinkBlue,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (snap.isCapturingAudio || uiState.micTestRunning) {
                    LiveAudioMeterSection(
                        isCapturingAudio = snap.isCapturingAudio,
                        liveMeterFlow = liveMeterFlow,
                        micTestDb = uiState.micTestTelemetry.audioLevelDb,
                        micTestProb = uiState.micTestTelemetry.vadProbability,
                        vadThreshold = uiState.settings.config.vadThreshold
                    )
                }

                val errorOrInterrupt = snap.interruptionReason ?: snap.lastError
                if (!errorOrInterrupt.isNullOrBlank()) {
                    Text(errorOrInterrupt, color = RecorderGalleryColors.CoralAlert, style = MaterialTheme.typography.bodySmall)
                    RecorderPillButton(
                        text = "Acknowledge",
                        compact = true,
                        onClick = onAcknowledgeInterruption
                    )
                }

                if (!uiState.permissions.canRecordAudio) {
                    RecorderPillButton(
                        text = "Restore Microphone Permission",
                        icon = Icons.Default.Mic,
                        containerColor = RecorderGalleryColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (!snap.recorderState.isArmedOrCapturing && !uiState.settings.privacyKillSwitch) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecorderPillButton(
                            text = "Arm & Start Recording",
                            icon = Icons.Default.Mic,
                            containerColor = RecorderGalleryColors.HeroCoral,
                            contentColor = Color.White,
                            onClick = onArmAndStart,
                            modifier = Modifier.weight(1f)
                        )
                        RecorderPillButton(
                            text = if (uiState.micTestRunning) "Stop Test" else "Mic Test",
                            onClick = onToggleMicTest
                        )
                    }
                }
            }
        }

        item {
            RecorderSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (uiState.bleHostState.isHosting) RecorderGalleryColors.BlueSoft else RecorderGalleryColors.PillSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = if (uiState.bleHostState.isHosting) RecorderGalleryColors.LinkBlue else RecorderGalleryColors.MutedText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bluetooth Link (Independent)", style = MaterialTheme.typography.labelMedium, color = RecorderGalleryColors.MutedText)
                        Text(formatRecorderHumanLabel(uiState.bleState.name), fontWeight = FontWeight.SemiBold, color = RecorderGalleryColors.PrimaryInk)
                        Text(
                            text = if (uiState.bleHostState.isHosting) {
                                "GATT Host Active • Peer: ${uiState.pairingState.pairedClientName ?: "Unpaired"}"
                            } else {
                                "GATT Host Stopped • Tap BLE to enable"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = RecorderGalleryColors.SecondaryInk
                        )
                    }
                    RecorderPillButton(
                        text = "BLE",
                        compact = true,
                        onClick = onOpenPairingTab
                    )
                }
            }
        }

        uiState.integrityCheckMessage?.let { msg ->
            item {
                RecorderSurfaceCard(
                    containerColor = RecorderGalleryColors.BlueSoft,
                    borderColor = RecorderGalleryColors.LinkBlue.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
                        TextButton(onClick = onClearIntegrityMsg) { Text("Dismiss", color = RecorderGalleryColors.LinkBlue) }
                    }
                }
            }
        }

        item {
            RecorderSectionLabel("Recent encrypted audio chunks")
            Text(
                "Stores AES-256-GCM encrypted WAV speech chunks locally and syncs automatically to Primary for transcription & AI memory.",
                style = MaterialTheme.typography.bodySmall,
                color = RecorderGalleryColors.SecondaryInk,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        if (uiState.recentSegments.isEmpty()) {
            item {
                RecorderSurfaceCard {
                    Text(
                        "No speech segments saved yet. Continuous silence is discarded automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RecorderGalleryColors.SecondaryInk
                    )
                }
            }
        } else {
            items(uiState.recentSegments, key = { it.segmentId }) { seg ->
                RecorderSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Segment #${seg.sequenceNumber} • ${"%.1f".format(seg.duration / 1000.0)}s",
                                fontWeight = FontWeight.SemiBold,
                                color = RecorderGalleryColors.LinkBlue
                            )
                            Text(
                                text = "${formatter.format(Date(seg.startTimestamp))} • ${formatRecorderBytes(seg.encryptedSize)} • ${formatRecorderHumanLabel(seg.uploadStatus.name)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = RecorderGalleryColors.PrimaryInk
                            )
                            Text(
                                text = "SHA-256: ${seg.checksum.take(16)}…",
                                style = MaterialTheme.typography.labelSmall,
                                color = RecorderGalleryColors.MutedText
                            )
                        }
                        RecorderPillButton(
                            text = "Verify",
                            compact = true,
                            onClick = { onVerifySegment(seg.segmentId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveAudioMeterSection(
    isCapturingAudio: Boolean,
    liveMeterFlow: StateFlow<LiveMeterSnapshot>,
    micTestDb: Float,
    micTestProb: Float,
    vadThreshold: Float
) {
    // Scoped collector: only this tiny 3-line meter recomposes at ~10 Hz, keeping the rest of the screen flicker-free.
    val meter by liveMeterFlow.collectAsStateWithLifecycle()
    val prob = if (isCapturingAudio) meter.vadProbability else micTestProb
    val db = if (isCapturingAudio) meter.audioLevelDb else micTestDb
    val segSec = if (isCapturingAudio) meter.activeSegmentDurationMs / 1000.0 else 0.0
    Text("Audio: ${"%.1f".format(db)} dBFS • Active Clip: ${"%.1f".format(segSec)}s", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
    Text("Silero VAD Prob: ${"%.2f".format(prob)} (threshold ${"%.2f".format(vadThreshold)})", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.MutedText)
    LinearProgressIndicator(
        progress = { prob.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp)),
        color = RecorderGalleryColors.HeroCoral,
        trackColor = RecorderGalleryColors.HeroCoralSoft
    )
}

@Composable
private fun BatteryAndStorageTab(
    uiState: RecorderUiState,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    val health = uiState.health
    val totalMb = (health.totalStorageBytes / (1024L * 1024L)).coerceAtLeast(1L)
    val freeMb = health.availableStorageBytes / (1024L * 1024L)
    val usedMb = (totalMb - freeMb).coerceAtLeast(0L)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RecorderSectionLabel("Battery & thermal")
        RecorderSurfaceCard {
            Text("Battery Level: ${if (health.batteryPct >= 0) "${health.batteryPct}%" else "--"} (${if (health.isCharging) "Charging" else "On Battery"})", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
            Text("Power Save Mode: ${if (health.isPowerSaveMode) "Active" else "Off"}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Text("Thermal Status: ${health.thermalLabel}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Text("Heap Usage: ${health.heapUsedMb} / ${health.heapMaxMb} MB", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Text("Battery Exemption: ${if (uiState.permissions.isBatteryOptimizationIgnored) "Unrestricted" else "Standard"}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
        }

        RecorderSectionLabel("Storage")
        RecorderSurfaceCard {
            Text("Free Space: $freeMb MB / $totalMb MB", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
            LinearProgressIndicator(
                progress = { usedMb / totalMb.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)),
                color = RecorderGalleryColors.LinkBlue,
                trackColor = RecorderGalleryColors.PillSurface
            )
            Text("Encrypted Segments Used: ${formatRecorderBytes(uiState.totalStoredBytes)} across ${uiState.totalSegmentCount} files", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Text("Safety Stop Floor: ${uiState.settings.config.minFreeStorageMb} MB", style = MaterialTheme.typography.labelSmall, color = RecorderGalleryColors.MutedText)
        }

        RecorderSurfaceCard {
            Text(uiState.permissions.oemGuidanceTitle, fontWeight = FontWeight.SemiBold, color = RecorderGalleryColors.PrimaryInk)
            Text(uiState.permissions.oemGuidanceBody, style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecorderPillButton(text = "Battery Exemption", compact = true, onClick = onOpenBatterySettings)
                RecorderPillButton(text = "App Settings", compact = true, onClick = onOpenAppSettings)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticsAndTuningTab(
    uiState: RecorderUiState,
    onUpdateConfig: (RecorderConfigPayload) -> Unit,
    onSetAudioSource: (AudioSourcePreset) -> Unit
) {
    val cfg = uiState.settings.config
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = RecorderGalleryColors.PillSurface,
        labelColor = RecorderGalleryColors.SecondaryInk,
        selectedContainerColor = RecorderGalleryColors.BlueSoft,
        selectedLabelColor = RecorderGalleryColors.LinkBlue
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            RecorderSectionLabel("VAD & segmentation tuning")
            RecorderSurfaceCard {
                var draftVad by remember(cfg.vadThreshold) { mutableFloatStateOf(cfg.vadThreshold) }
                var draftSilenceMs by remember(cfg.silenceTimeoutMs) { mutableFloatStateOf(cfg.silenceTimeoutMs.toFloat()) }
                var draftMaxSec by remember(cfg.maxSegmentDurationMs) { mutableFloatStateOf((cfg.maxSegmentDurationMs / 1000).toFloat()) }

                Text("Silero Speech Threshold: ${"%.2f".format(draftVad)}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
                Slider(
                    value = draftVad,
                    onValueChange = { draftVad = it },
                    onValueChangeFinished = { onUpdateConfig(cfg.copy(vadThreshold = draftVad)) },
                    valueRange = 0.20f..0.85f
                )

                Text("Silence Timeout: ${(draftSilenceMs.toInt() / 100) * 100} ms", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
                Slider(
                    value = draftSilenceMs,
                    onValueChange = { draftSilenceMs = it },
                    onValueChangeFinished = {
                        onUpdateConfig(cfg.copy(silenceTimeoutMs = (draftSilenceMs.toInt() / 100) * 100))
                    },
                    valueRange = 800f..3500f
                )

                Text("Max Segment Duration: ${draftMaxSec.toInt()} s", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
                Slider(
                    value = draftMaxSec,
                    onValueChange = { draftMaxSec = it },
                    onValueChangeFinished = {
                        onUpdateConfig(cfg.copy(maxSegmentDurationMs = draftMaxSec.toInt() * 1000))
                    },
                    valueRange = 15f..60f
                )

                Text("Audio Source Preset", style = MaterialTheme.typography.labelMedium, color = RecorderGalleryColors.MutedText)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AudioSourcePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = cfg.audioSource == preset,
                            onClick = { onSetAudioSource(preset) },
                            colors = chipColors,
                            border = null,
                            shape = RoundedCornerShape(50),
                            label = { Text(formatRecorderHumanLabel(preset.name), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text(
                    "VAD Engine: ${uiState.micTestTelemetry.vadBackendLabel} • Overruns: ${uiState.micTestTelemetry.bufferOverrunCount} • Silenced: ${if (uiState.micTestTelemetry.isClientSilenced) "Yes" else "No"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecorderGalleryColors.MutedText
                )
                uiState.micTestTelemetry.vadFallbackReason?.let { reason ->
                    Text(
                        "VAD fallback active: $reason",
                        style = MaterialTheme.typography.labelSmall,
                        color = RecorderGalleryColors.CoralAlert
                    )
                }
            }
        }

        item {
            RecorderSectionLabel("Event log (${uiState.diagnostics.size})")
        }

        items(uiState.diagnostics, key = { it.eventId }) { ev ->
            RecorderSurfaceCard {
                Text(
                    text = "${formatter.format(Date(ev.timestamp))} • [${ev.severity}] ${ev.category}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = RecorderGalleryColors.LinkBlue
                )
                Text(ev.message, style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
                Text(
                    text = "Recorder: ${formatRecorderHumanLabel(ev.recorderState.name)} • BLE: ${formatRecorderHumanLabel(ev.bleState.name)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = RecorderGalleryColors.MutedText
                )
            }
        }
    }
}

@Composable
fun BluetoothPairingCard(
    uiState: RecorderUiState,
    onStartBleHost: () -> Unit,
    onStopBleHost: () -> Unit,
    onTogglePairingWindow: (Boolean) -> Unit,
    onApprovePairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onUnpair: () -> Unit,
    showHostControls: Boolean = true,
    showUnpairAction: Boolean = true
) {
    val host = uiState.bleHostState
    val pairing = uiState.pairingState
    var confirmUnpair by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RecorderSurfaceCard {
            Text("BLE GATT Server & Advertising", fontWeight = FontWeight.SemiBold, color = RecorderGalleryColors.PrimaryInk)
            Text("Link State: ${formatRecorderHumanLabel(uiState.bleState.name)}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
            Text(
                "GATT Host: ${if (host.isHosting) "Active" else "Stopped"} • Advertising: ${if (host.isAdvertising) "On" else "Off"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (host.isHosting) RecorderGalleryColors.Emerald else RecorderGalleryColors.MutedText
            )
            Text(
                "Negotiated MTU: ${host.negotiatedMtu} • Peer Addr: ${host.connectedDeviceAddress ?: "--"}",
                style = MaterialTheme.typography.labelSmall,
                color = RecorderGalleryColors.MutedText
            )
            host.lastHostError?.let { err ->
                Text(err, color = RecorderGalleryColors.CoralAlert, style = MaterialTheme.typography.bodySmall)
            }
            if (showHostControls) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!host.isHosting) {
                        RecorderPillButton(
                            text = "Start BLE Host",
                            compact = true,
                            containerColor = RecorderGalleryColors.PrimaryInk,
                            contentColor = Color.White,
                            onClick = onStartBleHost
                        )
                    } else {
                        RecorderPillButton(
                            text = "Stop BLE Host",
                            compact = true,
                            onClick = onStopBleHost
                        )
                    }
                }
            }
        }

        RecorderSurfaceCard {
            Text("Primary Device Pairing", fontWeight = FontWeight.SemiBold, color = RecorderGalleryColors.PrimaryInk)
            if (pairing.isPaired) {
                Text("Paired Primary: ${pairing.pairedClientName ?: "Primary Phone"}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
                Text("Peer ID: ${pairing.pairedClientDeviceId?.take(12)?.plus("…") ?: "--"}", style = MaterialTheme.typography.labelSmall, color = RecorderGalleryColors.MutedText)
                if (showHostControls && showUnpairAction) {
                    RecorderPillButton(
                        text = "Unpair Primary Device",
                        compact = true,
                        containerColor = RecorderGalleryColors.HeroCoralSoft,
                        contentColor = RecorderGalleryColors.CoralAlert,
                        onClick = { confirmUnpair = true }
                    )
                }
            } else {
                Text("Not paired with any Primary device yet.", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.MutedText)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Allow Pairing Window", fontWeight = FontWeight.Medium, color = RecorderGalleryColors.PrimaryInk)
                    Text("Opens ECDH key exchange for your Primary phone.", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
                }
                Switch(
                    checked = pairing.pairingWindowOpen,
                    onCheckedChange = onTogglePairingWindow
                )
            }

            val pendingSas = pairing.pendingSasCode
            if (pendingSas != null) {
                RecorderSurfaceCard(
                    containerColor = RecorderGalleryColors.BlueSoft,
                    borderColor = RecorderGalleryColors.LinkBlue.copy(alpha = 0.45f)
                ) {
                    Text("Confirm Pairing Code on Both Phones", fontWeight = FontWeight.Bold, color = RecorderGalleryColors.PrimaryInk)
                    Text("Peer: ${pairing.pendingClientName ?: pairing.pendingClientId}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
                    Text(
                        text = "SAS Code: $pendingSas",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = RecorderGalleryColors.LinkBlue
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecorderPillButton(
                            text = "Approve",
                            compact = true,
                            containerColor = RecorderGalleryColors.PrimaryInk,
                            contentColor = Color.White,
                            onClick = onApprovePairing
                        )
                        RecorderPillButton(
                            text = "Reject",
                            compact = true,
                            containerColor = RecorderGalleryColors.CardSurface,
                            onClick = onRejectPairing
                        )
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            containerColor = RecorderGalleryColors.CardSurface,
            title = { Text("Unpair Primary Device?") },
            text = { Text("This clears Bluetooth pairing keys on this Secondary Recorder. Local encrypted recordings remain safe until synced again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    onUnpair()
                }) { Text("Unpair", color = RecorderGalleryColors.CoralAlert) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PairingManagementTab(
    uiState: RecorderUiState,
    onStartBleHost: () -> Unit,
    onStopBleHost: () -> Unit,
    onTogglePairingWindow: (Boolean) -> Unit,
    onApprovePairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onUnpair: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RecorderSectionLabel("Bluetooth pairing & GATT server")
        BluetoothPairingCard(
            uiState = uiState,
            onStartBleHost = onStartBleHost,
            onStopBleHost = onStopBleHost,
            onTogglePairingWindow = onTogglePairingWindow,
            onApprovePairing = onApprovePairing,
            onRejectPairing = onRejectPairing,
            onUnpair = onUnpair,
            showHostControls = true,
            showUnpairAction = true
        )
    }
}

@Composable
private fun PrivacySettingsTab(
    uiState: RecorderUiState,
    onSetPrivacyKillSwitch: (Boolean) -> Unit,
    onSetAutoDeleteSynced: (Boolean) -> Unit,
    onSetLocalOnlyPauseSync: (Boolean) -> Unit,
    onWipeAll: () -> Unit,
    onReopenOnboarding: () -> Unit,
    onSwitchToPrimary: (() -> Unit)? = null
) {
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmRoleSwitch by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (onSwitchToPrimary != null) {
            RecorderSurfaceCard {
                Text("Device Role: Secondary (Recorder Companion)", fontWeight = FontWeight.Bold, color = RecorderGalleryColors.LinkBlue)
                Text(
                    "This device is acting as your dedicated encrypted voice recorder. Switch to Primary mode anytime to use Sunbo — Your 2nd Mind AI, offline transcription, and memory search on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecorderGalleryColors.SecondaryInk
                )
                RecorderPillButton(
                    text = "Switch to Primary (Sunbo) Mode",
                    onClick = { confirmRoleSwitch = true },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        RecorderSectionLabel("Privacy & data controls")

        RecorderSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Disable Recorder (Privacy Kill-Switch)", fontWeight = FontWeight.SemiBold, color = RecorderGalleryColors.PrimaryInk)
                    Text("Immediately halts capture and prevents arming until re-enabled.", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
                }
                Switch(
                    checked = uiState.settings.privacyKillSwitch,
                    onCheckedChange = onSetPrivacyKillSwitch
                )
            }
        }

        RecorderSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Local Capture Only (Pause Sync & Transcripts)", fontWeight = FontWeight.SemiBold, color = RecorderGalleryColors.PrimaryInk)
                    Text("Keeps recording & flushing encrypted audio chunks locally while halting sync/transcription.", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
                }
                Switch(
                    checked = uiState.settings.config.localOnlyPauseSync,
                    onCheckedChange = onSetLocalOnlyPauseSync
                )
            }
        }

        RecorderSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Delete Verified Synced Audio", fontWeight = FontWeight.SemiBold, color = RecorderGalleryColors.PrimaryInk)
                    Text("Only removes segments after Primary sends checksum-verified ACK.", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
                }
                Switch(
                    checked = uiState.settings.autoDeleteSyncedSegments,
                    onCheckedChange = onSetAutoDeleteSynced
                )
            }
        }

        RecorderPillButton(
            text = "Delete All Local Recordings",
            icon = Icons.Default.DeleteForever,
            containerColor = RecorderGalleryColors.HeroCoralSoft,
            contentColor = RecorderGalleryColors.CoralAlert,
            onClick = { confirmWipe = true },
            modifier = Modifier.fillMaxWidth()
        )

        RecorderPillButton(
            text = "Re-run Setup & Permission Wizard",
            onClick = onReopenOnboarding,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = RecorderGalleryColors.CardSurface,
            title = { Text("Delete all local audio?") },
            text = { Text("This stops any active recording and permanently wipes all encrypted audio segments and sync queue entries on this Secondary Recorder.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    onWipeAll()
                }) { Text("Delete All", color = RecorderGalleryColors.CoralAlert) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmRoleSwitch && onSwitchToPrimary != null) {
        AlertDialog(
            onDismissRequest = { confirmRoleSwitch = false },
            containerColor = RecorderGalleryColors.CardSurface,
            title = { Text("Switch to Primary (Sunbo) Mode?") },
            text = { Text("Active microphone capture and BLE recorder hosting will stop safely. All locally stored recordings and transcripts remain preserved so you can switch back anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRoleSwitch = false
                    onSwitchToPrimary()
                }) { Text("Switch to Primary", color = RecorderGalleryColors.LinkBlue) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRoleSwitch = false }) { Text("Cancel") }
            }
        )
    }
}
