package com.personalai.primary.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personalai.primary.ui.theme.SecondMindColors

@Composable
fun RoleSelectionScreen(
    onSelectPrimary: () -> Unit,
    onSelectSecondary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SecondMindColors.Canvas)
    ) {
        GalleryTopBar(title = "Sunbo")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            GalleryHeroHeader(
                icon = Icons.Default.Psychology,
                title = "Sunbo",
                subtitle = "Your 2nd Mind — 100% private on-device voice memory & offline AI",
                accentColor = SecondMindColors.HeroCoral,
                metaCaption = "2 device roles available"
            )

            GallerySectionLabel("Choose how this device works")

            RoleOptionCard(
                recommendedLabel = "Recommended for your main daily phone",
                title = "Primary — Sunbo 2nd Mind",
                description = "Receives encrypted audio over Bluetooth, transcribes offline with Gemma 4 / Whisper / Cactus, extracts memories & tasks, and answers cited questions on-device.",
                highlights = listOf(
                    "Offline speech-to-text (English, Hindi & Hinglish)",
                    "Local Gemma 4 Ask AI with cited transcripts",
                    "Memory Vault, Focus Tasks, Insights & WAV export",
                    "Encrypted Bluetooth auto-sync & remote controls"
                ),
                icon = Icons.Default.Psychology,
                accentColor = SecondMindColors.HeroCoral,
                ctaLabel = "Continue as Primary Device",
                isPrimaryCta = true,
                onClick = onSelectPrimary
            )

            RoleOptionCard(
                recommendedLabel = null,
                title = "Secondary — Recorder Companion",
                description = "Turns a spare phone or tablet into a private hands-free speech recorder with on-device Silero VAD and Hardware Keystore AES-256-GCM encryption.",
                highlights = listOf(
                    "On-device Silero VAD captures speech only (skips silence)",
                    "AES-256-GCM encryption per chunk in Android Keystore",
                    "Keeps recording locally even if Bluetooth disconnects",
                    "Mutual 6-digit SAS pairing & encrypted L2CAP sync"
                ),
                icon = Icons.Default.Mic,
                accentColor = SecondMindColors.CyanAccent,
                ctaLabel = "Continue as Secondary Recorder",
                isPrimaryCta = false,
                onClick = onSelectSecondary
            )

            MindSurfaceCard(containerColor = SecondMindColors.CardSurface) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = SecondMindColors.EmeraldPrivacy,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Zero cloud AI APIs. You can switch between Primary and Secondary modes anytime in Settings without losing stored data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondMindColors.SecondaryInk
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun RoleOptionCard(
    recommendedLabel: String?,
    title: String,
    description: String,
    highlights: List<String>,
    icon: ImageVector,
    accentColor: Color,
    ctaLabel: String,
    isPrimaryCta: Boolean,
    onClick: () -> Unit
) {
    MindSurfaceCard {
        if (!recommendedLabel.isNullOrBlank()) {
            StarEyebrowBadge(label = recommendedLabel)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = SecondMindColors.PrimaryInk
            )
        }

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = SecondMindColors.SecondaryInk
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            highlights.forEach { bullet ->
                Text(
                    text = "• $bullet",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondMindColors.PrimaryInk.copy(alpha = 0.85f)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        SoftPillButton(
            text = ctaLabel,
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            onClick = onClick,
            containerColor = if (isPrimaryCta) SecondMindColors.PrimaryInk else SecondMindColors.CardElevated,
            contentColor = if (isPrimaryCta) Color.White else SecondMindColors.PrimaryInk,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
