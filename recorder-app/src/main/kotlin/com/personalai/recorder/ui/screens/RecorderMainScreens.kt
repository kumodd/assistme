package com.personalai.recorder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personalai.protocol.AudioSourcePreset
import com.personalai.protocol.RecorderConfigPayload
import com.personalai.protocol.RecorderState
import com.personalai.recorder.ui.RecorderUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecorderOperationalShell(
    uiState: RecorderUiState,
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
    onToggleMicTest: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                    label = { Text("Status") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                    label = { Text("Pairing") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null) },
                    label = { Text("Health") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Diag") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.PrivacyTip, contentDescription = null) },
                    label = { Text("Privacy") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            EmergencyStopBanner(
                recorderState = uiState.snapshot.recorderState,
                onEmergencyStop = onEmergencyStop
            )

            when (selectedTab) {
                0 -> StatusAndSegmentsTab(
                    uiState = uiState,
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
                    onReopenOnboarding = onReopenOnboarding
                )
            }
        }
    }
}

@Composable
private fun EmergencyStopBanner(
    recorderState: RecorderState,
    onEmergencyStop: () -> Unit
) {
    if (recorderState.isArmedOrCapturing) {
        Button(
            onClick = onEmergencyStop,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Text("  EMERGENCY STOP RECORDING", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusAndSegmentsTab(
    uiState: RecorderUiState,
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recorder State", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = snap.recorderState.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (snap.recorderState.isArmedOrCapturing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text("Input: ${snap.inputDeviceName} • Session Segments: ${snap.sessionSegmentsCount}")
                    Text("Stored Total: ${uiState.totalSegmentCount} • Unsynced Queue: ${uiState.pendingSyncCount}")
                    if (uiState.settings.config.localOnlyPauseSync) {
                        Text(
                            "Local Capture Only: Sync & Transcripts Paused",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    if (snap.isCapturingAudio || uiState.micTestRunning) {
                        val prob = if (snap.isCapturingAudio) snap.vadProbability else uiState.micTestTelemetry.vadProbability
                        val db = if (snap.isCapturingAudio) snap.audioLevelDb else uiState.micTestTelemetry.audioLevelDb
                        Text("Audio: ${"%.1f".format(db)} dBFS • Segment: ${"%.1f".format(snap.activeSegmentDurationMs / 1000.0)}s")
                        Text("Silero VAD Prob: ${"%.2f".format(prob)} (thr=${"%.2f".format(uiState.settings.config.vadThreshold)})")
                        LinearProgressIndicator(progress = { prob.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }

                    val errorOrInterrupt = snap.interruptionReason ?: snap.lastError
                    if (!errorOrInterrupt.isNullOrBlank()) {
                        Text(errorOrInterrupt, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onAcknowledgeInterruption) {
                            Text("Acknowledge")
                        }
                    }

                    if (!uiState.permissions.canRecordAudio) {
                        Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                            Text("Restore Microphone Permission")
                        }
                    } else if (!snap.recorderState.isArmedOrCapturing && !uiState.settings.privacyKillSwitch) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onArmAndStart, modifier = Modifier.weight(1f)) {
                                Text("Arm & Start Recording")
                            }
                            OutlinedButton(onClick = onToggleMicTest) {
                                Text(if (uiState.micTestRunning) "Stop Test" else "Mic Test")
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bluetooth Link (Independent)", style = MaterialTheme.typography.labelMedium)
                        Text(uiState.bleState.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (uiState.bleHostState.isHosting) {
                                "GATT Host Active • Peer: ${uiState.pairingState.pairedClientName ?: "Unpaired"}"
                            } else {
                                "GATT Host Stopped • Tap Pair to enable BLE"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(onClick = onOpenPairingTab) {
                        Text("BLE")
                    }
                }
            }
        }

        uiState.integrityCheckMessage?.let { msg ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onClearIntegrityMsg) { Text("Dismiss") }
                    }
                }
            }
        }

        item {
            Text("Recent Encrypted Audio Chunks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Stores AES-256-GCM encrypted WAV speech chunks locally and syncs automatically to Device A for transcription & AI memory.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (uiState.recentSegments.isEmpty()) {
            item {
                Text(
                    "No speech segments saved yet. Continuous silence is discarded automatically.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            items(uiState.recentSegments, key = { it.segmentId }) { seg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Segment #${seg.sequenceNumber} • ${seg.duration / 1000.0}s",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${formatter.format(Date(seg.startTimestamp))} • ${seg.encryptedSize / 1024} KB • ${seg.uploadStatus}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "SHA256: ${seg.checksum.take(16)}…",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        OutlinedButton(onClick = { onVerifySegment(seg.segmentId) }) {
                            Text("Verify")
                        }
                    }
                }
            }
        }
    }
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
        Text("Battery & Thermal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Battery Level: ${health.batteryPct}% (${if (health.isCharging) "Charging" else "On Battery"})")
                Text("Power Save Mode: ${if (health.isPowerSaveMode) "Active" else "Off"}")
                Text("Thermal Status: ${health.thermalLabel}")
                Text("Heap Usage: ${health.heapUsedMb} / ${health.heapMaxMb} MB")
                Text("Battery Exemption: ${if (uiState.permissions.isBatteryOptimizationIgnored) "Unrestricted" else "Standard"}")
            }
        }

        Text("Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Free Space: $freeMb MB / $totalMb MB")
                LinearProgressIndicator(progress = { usedMb / totalMb.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("Encrypted Segments Used: ${uiState.totalStoredBytes / 1024L} KB across ${uiState.totalSegmentCount} files")
                Text("Safety Stop Floor: ${uiState.settings.config.minFreeStorageMb} MB")
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiState.permissions.oemGuidanceTitle, fontWeight = FontWeight.SemiBold)
                Text(uiState.permissions.oemGuidanceBody, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenBatterySettings) { Text("Battery Exemption") }
                    OutlinedButton(onClick = onOpenAppSettings) { Text("App Settings") }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsAndTuningTab(
    uiState: RecorderUiState,
    onUpdateConfig: (RecorderConfigPayload) -> Unit,
    onSetAudioSource: (AudioSourcePreset) -> Unit
) {
    val cfg = uiState.settings.config
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("VAD & Segmentation Tuning", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Silero Speech Threshold: ${"%.2f".format(cfg.vadThreshold)}")
                    Slider(
                        value = cfg.vadThreshold,
                        onValueChange = { onUpdateConfig(cfg.copy(vadThreshold = it)) },
                        valueRange = 0.20f..0.85f
                    )

                    Text("Silence Timeout: ${cfg.silenceTimeoutMs} ms")
                    Slider(
                        value = cfg.silenceTimeoutMs.toFloat(),
                        onValueChange = { onUpdateConfig(cfg.copy(silenceTimeoutMs = (it.toInt() / 100) * 100)) },
                        valueRange = 500f..3000f
                    )

                    Text("Max Segment Duration: ${cfg.maxSegmentDurationMs / 1000} s")
                    Slider(
                        value = (cfg.maxSegmentDurationMs / 1000).toFloat(),
                        onValueChange = { onUpdateConfig(cfg.copy(maxSegmentDurationMs = it.toInt() * 1000)) },
                        valueRange = 10f..60f
                    )

                    Text("Audio Source Preset", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioSourcePreset.entries.forEach { preset ->
                            FilterChip(
                                selected = cfg.audioSource == preset,
                                onClick = { onSetAudioSource(preset) },
                                label = { Text(preset.name.replace("_", " "), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Text(
                        "Buffer Overruns: ${uiState.micTestTelemetry.bufferOverrunCount} • Silenced: ${uiState.micTestTelemetry.isClientSilenced}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Text("Event Log (${uiState.diagnostics.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        items(uiState.diagnostics, key = { it.eventId }) { ev ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${formatter.format(Date(ev.timestamp))} • [${ev.severity}] ${ev.category}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(ev.message, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "Rec=${ev.recorderState} | BLE=${ev.bleState}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
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
    showHostControls: Boolean = true
) {
    val host = uiState.bleHostState
    val pairing = uiState.pairingState

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BLE GATT Server & Advertising", fontWeight = FontWeight.SemiBold)
                Text("Link State: ${uiState.bleState.name}")
                Text("Host Running: ${host.isHosting} • Advertising: ${host.isAdvertising}")
                Text("Negotiated MTU: ${host.negotiatedMtu} • Peer Addr: ${host.connectedDeviceAddress ?: "-"}")
                host.lastHostError?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (showHostControls) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!host.isHosting) {
                            Button(onClick = onStartBleHost) { Text("Start BLE Host") }
                        } else {
                            OutlinedButton(onClick = onStopBleHost) { Text("Stop BLE Host") }
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device A Pairing", fontWeight = FontWeight.SemiBold)
                if (pairing.isPaired) {
                    Text("Paired Peer: ${pairing.pairedClientName ?: "Device A"}")
                    Text("Peer ID: ${pairing.pairedClientDeviceId}", style = MaterialTheme.typography.bodySmall)
                    if (showHostControls) {
                        OutlinedButton(onClick = onUnpair) { Text("Unpair Device A") }
                    }
                } else {
                    Text("Not paired with any primary device yet.", style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Allow Pairing Window", fontWeight = FontWeight.Medium)
                        Text("Opens ECDH key exchange for Device A.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = pairing.pairingWindowOpen,
                        onCheckedChange = onTogglePairingWindow
                    )
                }

                val pendingSas = pairing.pendingSasCode
                if (pendingSas != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Confirm Pairing Code on Both Phones", fontWeight = FontWeight.Bold)
                            Text("Peer: ${pairing.pendingClientName ?: pairing.pendingClientId}")
                            Text(
                                text = "SAS Code: $pendingSas",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onApprovePairing) { Text("Approve") }
                                OutlinedButton(onClick = onRejectPairing) { Text("Reject") }
                            }
                        }
                    }
                }
            }
        }
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
        Text("Bluetooth Pairing & GATT Server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        BluetoothPairingCard(
            uiState = uiState,
            onStartBleHost = onStartBleHost,
            onStopBleHost = onStopBleHost,
            onTogglePairingWindow = onTogglePairingWindow,
            onApprovePairing = onApprovePairing,
            onRejectPairing = onRejectPairing,
            onUnpair = onUnpair,
            showHostControls = true
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
    onReopenOnboarding: () -> Unit
) {
    var confirmWipe by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Privacy & Data Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Disable Recorder (Privacy Kill-Switch)", fontWeight = FontWeight.SemiBold)
                    Text("Immediately halts capture and prevents arming until re-enabled.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = uiState.settings.privacyKillSwitch,
                    onCheckedChange = onSetPrivacyKillSwitch
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Local Capture Only (Pause Sync & Transcripts)", fontWeight = FontWeight.SemiBold)
                    Text("Keeps recording & flushing encrypted audio chunks locally while halting sync/transcription.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = uiState.settings.config.localOnlyPauseSync,
                    onCheckedChange = onSetLocalOnlyPauseSync
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Delete Verified Synced Audio", fontWeight = FontWeight.SemiBold)
                    Text("Only removes segments after Device A sends checksum-verified ACK.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = uiState.settings.autoDeleteSyncedSegments,
                    onCheckedChange = onSetAutoDeleteSynced
                )
            }
        }

        Button(
            onClick = { confirmWipe = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.DeleteForever, contentDescription = null)
            Text("  Delete All Local Recordings")
        }

        OutlinedButton(onClick = onReopenOnboarding, modifier = Modifier.fillMaxWidth()) {
            Text("Re-run Setup & Permission Wizard")
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all local audio?") },
            text = { Text("This stops any active recording and permanently wipes all encrypted audio segments and sync queue entries on Device B.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    onWipeAll()
                }) { Text("Delete All") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Cancel") }
            }
        )
    }
}
