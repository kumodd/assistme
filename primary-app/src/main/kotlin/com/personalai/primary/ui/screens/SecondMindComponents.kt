package com.personalai.primary.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalai.primary.audio.AudioRetentionPolicy
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.memory.CategoryShare
import com.personalai.primary.memory.DailyActivityBucket
import com.personalai.primary.ui.theme.SecondMindColors

@Composable
fun MindSurfaceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = SecondMindColors.CardSurface,
    borderColor: Color = SecondMindColors.HairlineBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun StatusPill(
    label: String,
    dotColor: Color,
    containerColor: Color = SecondMindColors.CardElevated,
    onClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.35f)),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MetricTile(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = SecondMindColors.CardElevated,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = SecondMindColors.MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun WeeklyActivityBarChart(
    buckets: List<DailyActivityBucket>,
    modifier: Modifier = Modifier
) {
    if (buckets.isEmpty()) return
    val maxVal = buckets.maxOf { maxOf(it.segmentCount, it.memoryCount) }.coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("7-Day Capture Activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot("Audio Clips", SecondMindColors.CyanAccent)
                LegendDot("Memories", SecondMindColors.VioletAi)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            buckets.forEachIndexed { idx, bucket ->
                val isToday = idx == buckets.lastIndex
                val segFrac = (bucket.segmentCount.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
                val memFrac = (bucket.memoryCount.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = if (bucket.segmentCount > 0 || bucket.memoryCount > 0) "${bucket.segmentCount}/${bucket.memoryCount}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.MutedText,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.height(66.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Box(
                            modifier = Modifier
                                .width(10.dp)
                                .height((62f * segFrac).coerceAtLeast(if (bucket.segmentCount > 0) 6f else 2f).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (isToday) SecondMindColors.CyanAccent else SecondMindColors.CyanAccent.copy(alpha = 0.55f))
                        )
                        Box(
                            modifier = Modifier
                                .width(10.dp)
                                .height((62f * memFrac).coerceAtLeast(if (bucket.memoryCount > 0) 6f else 2f).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (isToday) SecondMindColors.VioletAi else SecondMindColors.VioletAi.copy(alpha = 0.55f))
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = bucket.dayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) SecondMindColors.CyanAccent else SecondMindColors.MutedText,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = SecondMindColors.MutedText)
    }
}

@Composable
fun CategoryBreakdownBars(
    shares: List<CategoryShare>,
    onSelectCategory: (MemoryCategory) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shares.forEach { share ->
            val accent = categoryAccentColor(share.category)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectCategory(share.category) }
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = share.category.name.replace('_', ' '),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${share.count} (${(share.ratio * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                }
                LinearProgressIndicator(
                    progress = { share.ratio.coerceIn(0.03f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = accent,
                    trackColor = SecondMindColors.CardElevated
                )
            }
        }
    }
}

@Composable
fun AudioRetentionBarCard(
    policy: AudioRetentionPolicy,
    activeAudioChunkCount: Int,
    activeAudioBytes: Long,
    onSelectPolicy: (AudioRetentionPolicy) -> Unit,
    onPurgeExpiredNow: () -> Unit,
    compact: Boolean = false
) {
    MindSurfaceCard(
        containerColor = SecondMindColors.CardSurface,
        borderColor = SecondMindColors.HairlineBorder
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Storage, contentDescription = null, tint = SecondMindColors.CyanAccent, modifier = Modifier.size(18.dp))
                Column {
                    Text("Audio Chunk Auto-Deletion", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$activeAudioChunkCount audio chunk(s) • ${formatStorageBytes(activeAudioBytes)} stored locally",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.MutedText
                    )
                }
            }
            OutlinedButton(onClick = onPurgeExpiredNow) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Clean", style = MaterialTheme.typography.labelMedium)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AudioRetentionPolicy.entries.forEach { option ->
                FilterChip(
                    selected = policy == option,
                    onClick = { onSelectPolicy(option) },
                    label = { Text(option.shortLabel, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        if (!compact) {
            Text(
                "Auto-prunes completed encrypted audio chunks older than ${policy.shortLabel} while keeping 100% of transcripts, memories, and tasks intact. Use Save WAV on any clip to keep a permanent copy in Downloads/SecondMind.",
                style = MaterialTheme.typography.bodySmall,
                color = SecondMindColors.MutedText
            )
        }
    }
}

@Composable
fun QuickCaptureThoughtDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, category: MemoryCategory, pin: Boolean, createTask: Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MemoryCategory.IDEA) }
    var pin by remember { mutableStateOf(true) }
    var createTask by remember { mutableStateOf(false) }

    val quickCategories = remember {
        listOf(
            MemoryCategory.IDEA,
            MemoryCategory.TASK,
            MemoryCategory.DECISION,
            MemoryCategory.FACT,
            MemoryCategory.COMMITMENT,
            MemoryCategory.PROJECT,
            MemoryCategory.FOLLOW_UP
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture to 2nd Mind") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Headline (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Thought, decision, or task...") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickCategories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Pin to Today Briefing", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = pin, onCheckedChange = { pin = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Also add to Open Tasks", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = createTask, onCheckedChange = { createTask = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, content, category, pin, createTask) },
                enabled = content.isNotBlank()
            ) { Text("Save Thought") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun EditMemoryDialog(
    memory: MemoryItemEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, category: MemoryCategory) -> Unit
) {
    var title by remember(memory.memoryId) { mutableStateOf(memory.title) }
    var content by remember(memory.memoryId) { mutableStateOf(memory.content) }
    var category by remember(memory.memoryId) { mutableStateOf(memory.category) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MemoryCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, content, category) },
                enabled = content.isNotBlank()
            ) { Text("Save Changes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun formatDurationFriendly(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "0s"
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return when {
        mins >= 60 -> "${mins / 60}h ${mins % 60}m"
        mins > 0 -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}

fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    if (bytes < 1024L * 1024L) {
        return "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
    return "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

fun categoryAccentColor(category: MemoryCategory): Color = when (category) {
    MemoryCategory.TASK, MemoryCategory.COMMITMENT, MemoryCategory.DEADLINE, MemoryCategory.FOLLOW_UP -> SecondMindColors.AmberHighlight
    MemoryCategory.DECISION, MemoryCategory.PROJECT, MemoryCategory.MEETING_NOTE -> SecondMindColors.EmeraldPrivacy
    MemoryCategory.IDEA, MemoryCategory.QUESTION -> SecondMindColors.VioletAi
    else -> SecondMindColors.CyanAccent
}
