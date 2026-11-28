package com.personalai.recorder.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personalai.recorder.ui.RecorderUiState

@Composable
fun OnboardingFlowScreen(
    uiState: RecorderUiState,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInitializeIdentity: () -> Unit,
    onStartBleHost: () -> Unit = {},
    onStopBleHost: () -> Unit = {},
    onTogglePairingWindow: (Boolean) -> Unit,
    onApprovePairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onToggleMicTest: () -> Unit,
    onStopMicTest: () -> Unit,
    onCompleteAndArm: () -> Unit,
    onCompleteOnly: () -> Unit,
    onSwitchRole: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val totalSteps = 5

    BackHandler(enabled = step > 0) {
        if (step == 3) onStopMicTest()
        step--
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RecorderGalleryColors.Canvas)
    ) {
        RecorderGalleryTopBar(
            title = "Secondary Setup (${step + 1}/$totalSteps)",
            centered = false,
            trailing = if (onSwitchRole != null) {
                {
                    RecorderPillButton(
                        text = "Switch Role",
                        compact = true,
                        onClick = {
                            onStopMicTest()
                            onSwitchRole()
                        }
                    )
                }
            } else null
        )

        LinearProgressIndicator(
            progress = { (step + 1) / totalSteps.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = RecorderGalleryColors.HeroCoral,
            trackColor = RecorderGalleryColors.HeroCoralSoft
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                    onStartBleHost = onStartBleHost,
                    onStopBleHost = onStopBleHost,
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

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    RecorderPillButton(
                        text = "Back",
                        onClick = {
                            if (step == 3) onStopMicTest()
                            step--
                        }
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (step < 4) {
                    RecorderPillButton(
                        text = if (step == 1 && !uiState.permissions.canRecordAudio) "Grant Mic First" else "Continue",
                        enabled = when (step) {
                            1 -> uiState.permissions.canRecordAudio
                            else -> true
                        },
                        containerColor = RecorderGalleryColors.PrimaryInk,
                        contentColor = Color.White,
                        onClick = {
                            if (step == 0) onInitializeIdentity()
                            if (step == 3) onStopMicTest()
                            step++
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onInitializeIdentity: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(RecorderGalleryColors.HeroCoral),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
        Text(
            text = "Dedicated AI Memory Recorder",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = RecorderGalleryColors.PrimaryInk,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Captures spoken speech segments with on-device Silero VAD, encrypts them locally with Android Keystore, and syncs to your Primary phone over Bluetooth.",
            style = MaterialTheme.typography.bodyMedium,
            color = RecorderGalleryColors.SecondaryInk,
            textAlign = TextAlign.Center
        )
        RecorderSurfaceCard {
            Text("Transparency & Android Rules", fontWeight = FontWeight.SemiBold, color = RecorderGalleryColors.PrimaryInk)
            Text("• Recording always shows a persistent notification and Android's microphone indicator.", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Text("• Recording continues independently if Bluetooth disconnects.", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Text("• Android does not guarantee uninterrupted capture across force-stop, reboots, cellular/VoIP calls, or aggressive OEM task killers.", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Text("• Always obtain consent from people in recorded conversations.", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
        }
        RecorderPillButton(
            text = "Pre-Initialize Encrypted Storage",
            icon = Icons.Default.Lock,
            onClick = onInitializeIdentity,
            modifier = Modifier.fillMaxWidth()
        )
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
            text = "Permissions & Reliability",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = RecorderGalleryColors.PrimaryInk
        )
        Text(
            "Grant runtime permissions so Silero VAD capture, notification indicator, and Bluetooth pairing work cleanly.",
            style = MaterialTheme.typography.bodySmall,
            color = RecorderGalleryColors.SecondaryInk
        )

        RecorderPillButton(
            text = "Request Runtime Permissions",
            icon = Icons.Default.Mic,
            containerColor = RecorderGalleryColors.PrimaryInk,
            contentColor = Color.White,
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        )

        uiState.permissions.items.forEach { item ->
            RecorderSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = if (item.isGranted) "Granted" else "Needed",
                        tint = if (item.isGranted) RecorderGalleryColors.Emerald else RecorderGalleryColors.CoralAlert
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${item.title} (${if (item.isGranted) "Granted" else "Needed"})",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = RecorderGalleryColors.PrimaryInk
                        )
                        Text(item.rationale, style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
                    }
                }
            }
        }

        RecorderSurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = RecorderGalleryColors.LinkBlue)
                Text(
                    text = if (uiState.permissions.isBatteryOptimizationIgnored) {
                        "Battery Optimization: Unrestricted"
                    } else {
                        "Battery Optimization: Standard (Recommended to exempt)"
                    },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RecorderGalleryColors.PrimaryInk
                )
            }
            Text(uiState.permissions.oemGuidanceTitle, style = MaterialTheme.typography.labelMedium, color = RecorderGalleryColors.PrimaryInk)
            Text(uiState.permissions.oemGuidanceBody, style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecorderPillButton(text = "Battery Exemption", compact = true, onClick = onOpenBatterySettings)
                RecorderPillButton(text = "OEM App Info", compact = true, onClick = onOpenAppSettings)
            }
        }
    }
}

@Composable
private fun StorageAndPairingStep(
    uiState: RecorderUiState,
    onInitializeIdentity: () -> Unit,
    onStartBleHost: () -> Unit,
    onStopBleHost: () -> Unit,
    onTogglePairingWindow: (Boolean) -> Unit,
    onApprovePairing: () -> Unit,
    onRejectPairing: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Encrypted Storage & Pairing",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = RecorderGalleryColors.PrimaryInk
        )
        RecorderSurfaceCard {
            Text("Keystore AES-256-GCM: ${if (uiState.keystoreReady) "Ready" else "Pending"}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
            Text("Device ID: ${uiState.device?.deviceId?.take(12)?.plus("…") ?: "Initializing..."}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Text("Device Name: ${uiState.device?.deviceName ?: "--"}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            Text("Free Storage: ${uiState.health.availableStorageBytes / (1024 * 1024)} MB", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
        }
        BluetoothPairingCard(
            uiState = uiState,
            onTogglePairingWindow = onTogglePairingWindow,
            onApprovePairing = onApprovePairing,
            onRejectPairing = onRejectPairing,
            onStartBleHost = onStartBleHost,
            onStopBleHost = onStopBleHost,
            onUnpair = {},
            showHostControls = true,
            showUnpairAction = false
        )
        if (!uiState.keystoreReady || uiState.device == null) {
            RecorderPillButton(
                text = "Initialize Keystore & Identity",
                containerColor = RecorderGalleryColors.PrimaryInk,
                contentColor = Color.White,
                onClick = onInitializeIdentity,
                modifier = Modifier.fillMaxWidth()
            )
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
            fontWeight = FontWeight.Bold,
            color = RecorderGalleryColors.PrimaryInk
        )
        Text(
            "Speak a few words to verify AudioRecord input and Silero VAD speech probability. Nothing is saved to disk during this test.",
            style = MaterialTheme.typography.bodySmall,
            color = RecorderGalleryColors.SecondaryInk
        )

        RecorderPillButton(
            text = if (uiState.micTestRunning) "Stop Mic Test" else "Start Mic Test",
            icon = Icons.Default.GraphicEq,
            containerColor = if (uiState.micTestRunning) RecorderGalleryColors.HeroCoralSoft else RecorderGalleryColors.PrimaryInk,
            contentColor = if (uiState.micTestRunning) RecorderGalleryColors.HeroCoral else Color.White,
            onClick = onToggleMicTest,
            modifier = Modifier.fillMaxWidth()
        )

        RecorderSurfaceCard {
            Text("Input Device: ${uiState.micTestTelemetry.inputDeviceName}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
            Text("Audio Level: ${"%.1f".format(uiState.micTestTelemetry.audioLevelDb)} dBFS", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.SecondaryInk)
            LinearProgressIndicator(
                progress = { dbNorm },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)),
                color = RecorderGalleryColors.LinkBlue,
                trackColor = RecorderGalleryColors.PillSurface
            )

            Text(
                text = "Silero Speech Prob: ${"%.2f".format(vadProb)} (Threshold: ${"%.2f".format(threshold)})",
                style = MaterialTheme.typography.bodySmall,
                color = if (vadProb >= threshold) RecorderGalleryColors.HeroCoral else RecorderGalleryColors.PrimaryInk
            )
            LinearProgressIndicator(
                progress = { vadProb },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)),
                color = RecorderGalleryColors.HeroCoral,
                trackColor = RecorderGalleryColors.HeroCoralSoft
            )
            if (uiState.micTestTelemetry.isClientSilenced) {
                Text(
                    text = "Microphone is currently silenced by another higher-priority app or call.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecorderGalleryColors.CoralAlert
                )
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
            fontWeight = FontWeight.Bold,
            color = RecorderGalleryColors.PrimaryInk
        )
        RecorderSurfaceCard {
            Text("Mic Permission: ${if (uiState.permissions.canRecordAudio) "Granted" else "Missing"}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
            Text("Keystore AES-256-GCM: ${if (uiState.keystoreReady) "Initialized" else "Pending"}", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
            Text("VAD Engine: Silero ONNX (16 kHz Mono PCM-16)", style = MaterialTheme.typography.bodySmall, color = RecorderGalleryColors.PrimaryInk)
            Text(
                "Pre-roll / Silence Timeout / Max Segment: ${uiState.settings.config.preRollMs} ms / ${uiState.settings.config.silenceTimeoutMs} ms / ${uiState.settings.config.maxSegmentDurationMs / 1000} s",
                style = MaterialTheme.typography.bodySmall,
                color = RecorderGalleryColors.SecondaryInk
            )
        }
        RecorderPillButton(
            text = "Finish Setup & Arm Recorder Now",
            icon = Icons.Default.Mic,
            enabled = uiState.permissions.canRecordAudio && !uiState.settings.privacyKillSwitch,
            containerColor = RecorderGalleryColors.HeroCoral,
            contentColor = Color.White,
            onClick = onCompleteAndArm,
            modifier = Modifier.fillMaxWidth()
        )
        RecorderPillButton(
            text = "Finish Setup (Stay Idle)",
            onClick = onCompleteOnly,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
