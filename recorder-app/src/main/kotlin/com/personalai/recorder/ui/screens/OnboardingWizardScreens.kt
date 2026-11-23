package com.personalai.recorder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personalai.recorder.ui.RecorderUiState

@Composable
fun OnboardingFlowScreen(
    uiState: RecorderUiState,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInitializeIdentity: () -> Unit,
    onTogglePairingWindow: (Boolean) -> Unit,
    onApprovePairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onToggleMicTest: () -> Unit,
    onStopMicTest: () -> Unit,
    onCompleteAndArm: () -> Unit,
    onCompleteOnly: () -> Unit,
    modifier: Modifier = Modifier
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val totalSteps = 5

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Device B Setup (${step + 1}/$totalSteps)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        LinearProgressIndicator(
            progress = { (step + 1) / totalSteps.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )

        when (step) {
            0 -> WelcomeStep(onInitializeIdentity = onInitializeIdentity)
            1 -> PermissionsStep(
                uiState = uiState,
                onRequestPermissions = onRequestPermissions,
                onOpenBatterySettings = onOpenBatterySettings,
                onOpenAppSettings = onOpenAppSettings
            )
            2 -> StorageAndPairingStep(
                uiState = uiState,
                onInitializeIdentity = onInitializeIdentity,
                onTogglePairingWindow = onTogglePairingWindow,
                onApprovePairing = onApprovePairing,
                onRejectPairing = onRejectPairing
            )
            3 -> MicTestStep(
                uiState = uiState,
                onToggleMicTest = onToggleMicTest
            )
            4 -> ReadinessStep(
                uiState = uiState,
                onCompleteAndArm = {
                    onStopMicTest()
                    onCompleteAndArm()
                },
                onCompleteOnly = {
                    onStopMicTest()
                    onCompleteOnly()
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (step < 4) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (step > 0) {
                    OutlinedButton(onClick = {
                        if (step == 3) onStopMicTest()
                        step--
                    }) {
                        Text("Back")
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Button(
                    onClick = {
                        if (step == 0) onInitializeIdentity()
                        if (step == 3) onStopMicTest()
                        step++
                    },
                    enabled = when (step) {
                        1 -> uiState.permissions.canRecordAudio
                        else -> true
                    }
                ) {
                    Text(if (step == 1 && !uiState.permissions.canRecordAudio) "Grant Mic First" else "Continue")
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onInitializeIdentity: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Dedicated AI Memory Recorder",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "This secondary smartphone (Device B) captures spoken speech segments using on-device Silero VAD, encrypts them locally with Android Keystore, and syncs to your primary phone (Device A) over BLE."
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Transparency & Android Rules", fontWeight = FontWeight.SemiBold)
                Text("• Recording always shows a persistent notification and Android's microphone indicator.")
                Text("• Recording continues independently if Bluetooth disconnects.")
                Text("• Android does not guarantee uninterrupted capture across force-stop, reboots, cellular/VoIP calls, or aggressive OEM task killers.")
                Text("• Always obtain consent from people in recorded conversations.")
            }
        }
        OutlinedButton(onClick = onInitializeIdentity) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Text("  Pre-Initialize Encrypted Storage", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun PermissionsStep(
    uiState: RecorderUiState,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Permissions & Reliability Setup",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text("Grant runtime permissions so VAD capture, notification indicator, and BLE pairing work cleanly.")

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Text("  Request Runtime Permissions", modifier = Modifier.padding(start = 6.dp))
        }

        uiState.permissions.items.forEach { item ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (item.isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${item.title} (${if (item.isGranted) "Granted" else "Needed"})",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(item.rationale, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                    Text(
                        text = if (uiState.permissions.isBatteryOptimizationIgnored) {
                            "Battery Optimization: Unrestricted"
                        } else {
                            "Battery Optimization: Standard (Recommended to exempt)"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(uiState.permissions.oemGuidanceTitle, style = MaterialTheme.typography.labelMedium)
                Text(uiState.permissions.oemGuidanceBody, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenBatterySettings) {
                        Text("Battery Exemption")
                    }
                    OutlinedButton(onClick = onOpenAppSettings) {
                        Text("OEM App Info")
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageAndPairingStep(
    uiState: RecorderUiState,
    onInitializeIdentity: () -> Unit,
    onTogglePairingWindow: (Boolean) -> Unit,
    onApprovePairing: () -> Unit,
    onRejectPairing: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Encrypted Storage & BLE Pairing",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Keystore AES-256-GCM: ${if (uiState.keystoreReady) "Ready" else "Pending"}")
                Text("Device ID: ${uiState.device?.deviceId ?: "Initializing..."}")
                Text("Device Name: ${uiState.device?.deviceName ?: "-"}")
                Text("Free Storage: ${uiState.health.availableStorageBytes / (1024 * 1024)} MB")
            }
        }
        BluetoothPairingCard(
            uiState = uiState,
            onTogglePairingWindow = onTogglePairingWindow,
            onApprovePairing = onApprovePairing,
            onRejectPairing = onRejectPairing,
            onStartBleHost = {},
            onStopBleHost = {},
            onUnpair = {},
            showHostControls = false
        )
        if (!uiState.keystoreReady || uiState.device == null) {
            Button(onClick = onInitializeIdentity) {
                Text("Initialize Keystore & Identity")
            }
        }
    }
}

@Composable
private fun MicTestStep(
    uiState: RecorderUiState,
    onToggleMicTest: () -> Unit
) {
    val dbNorm = ((uiState.micTestTelemetry.audioLevelDb + 90f) / 90f).coerceIn(0f, 1f)
    val vadProb = uiState.micTestTelemetry.vadProbability.coerceIn(0f, 1f)
    val threshold = uiState.settings.config.vadThreshold

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Microphone & Silero VAD Test",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text("Speak a few words to verify AudioRecord input and Silero VAD speech probability. Nothing is saved to disk during this test.")

        Button(
            onClick = onToggleMicTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = null)
            Text(
                text = if (uiState.micTestRunning) "  Stop Mic Test" else "  Start Mic Test",
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Input Device: ${uiState.micTestTelemetry.inputDeviceName}")
                Text("Audio Level: ${"%.1f".format(uiState.micTestTelemetry.audioLevelDb)} dBFS")
                LinearProgressIndicator(progress = { dbNorm }, modifier = Modifier.fillMaxWidth())

                Text(
                    text = "Silero Speech Prob: ${"%.2f".format(vadProb)} (Threshold: ${"%.2f".format(threshold)})",
                    color = if (vadProb >= threshold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                LinearProgressIndicator(progress = { vadProb }, modifier = Modifier.fillMaxWidth())
                if (uiState.micTestTelemetry.isClientSilenced) {
                    Text(
                        text = "Microphone is currently silenced by another higher-priority app/call.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessStep(
    uiState: RecorderUiState,
    onCompleteAndArm: () -> Unit,
    onCompleteOnly: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Recorder Ready",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Mic Permission: ${if (uiState.permissions.canRecordAudio) "Granted" else "Missing"}")
                Text("Keystore AES-256-GCM: ${if (uiState.keystoreReady) "Initialized" else "Pending"}")
                Text("VAD Engine: Silero ONNX (16 kHz Mono PCM-16)")
                Text("Pre-roll / Silence Timeout / Max Segment: ${uiState.settings.config.preRollMs} ms / ${uiState.settings.config.silenceTimeoutMs} ms / ${uiState.settings.config.maxSegmentDurationMs / 1000} s")
            }
        }
        Button(
            onClick = onCompleteAndArm,
            enabled = uiState.permissions.canRecordAudio && !uiState.settings.privacyKillSwitch,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Finish Setup & Arm Recorder Now")
        }
        OutlinedButton(
            onClick = onCompleteOnly,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Finish Setup (Stay Idle)")
        }
    }
}
