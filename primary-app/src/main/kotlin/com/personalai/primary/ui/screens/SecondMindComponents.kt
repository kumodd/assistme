package com.personalai.primary.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.personalai.primary.audio.AudioRetentionPolicy
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.TaskReminderEntity
import com.personalai.primary.memory.CategoryShare
import com.personalai.primary.memory.DailyActivityBucket
import com.personalai.primary.ui.theme.SecondMindColors

/**
 * Crisp pure-white top bar matching the reference Google AI Edge Gallery chrome.
 */
@Composable
fun GalleryTopBar(
    title: String,
    modifier: Modifier = Modifier,
    centered: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SecondMindColors.TopBarSurface
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
                    color = SecondMindColors.PrimaryInk,
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
            HorizontalDivider(thickness = 0.75.dp, color = SecondMindColors.HairlineBorder)
        }
    }
}

/**
 * Centered circular emblem + bold title + airy subtitle matching the reference hero header.
 */
@Composable
fun GalleryHeroHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accentColor: Color = SecondMindColors.HeroCoral,
    metaCaption: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(accentColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = SecondMindColors.PrimaryInk,
            textAlign = TextAlign.Center
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = SecondMindColors.SecondaryInk,
            textAlign = TextAlign.Center
        )
        if (!metaCaption.isNullOrBlank()) {
            Text(
                text = metaCaption,
                style = MaterialTheme.typography.bodySmall,
                color = SecondMindColors.MutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun StarEyebrowBadge(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = SecondMindColors.StarGold,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = SecondMindColors.MutedText
        )
    }
}

@Composable
fun GallerySectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = SecondMindColors.PrimaryInk,
        modifier = modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/**
 * Signature Google AI Edge Gallery soft blue-gray `#F0F4F9` full-pill button with dark `#1F1F1F` ink.
 */
@Composable
fun SoftPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    containerColor: Color = SecondMindColors.CardElevated,
    contentColor: Color = SecondMindColors.PrimaryInk
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = SecondMindColors.CardElevated.copy(alpha = 0.55f),
            disabledContentColor = SecondMindColors.MutedText
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

/**
 * Gallery-styled pill chip colors: crisp white on the blush canvas, soft `#F0F4F9` inside white cards,
 * and gentle editorial blue (or soft coral) when selected.
 */
@Composable
fun galleryFilterChipColors(
    onWhiteCard: Boolean = false,
    useCoralSelection: Boolean = false
): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = if (onWhiteCard) SecondMindColors.CardElevated else SecondMindColors.CardSurface,
    labelColor = SecondMindColors.SecondaryInk,
    iconColor = SecondMindColors.SecondaryInk,
    selectedContainerColor = if (useCoralSelection) SecondMindColors.HeroCoralSoft else SecondMindColors.BlueSoftContainer,
    selectedLabelColor = if (useCoralSelection) SecondMindColors.HeroCoral else SecondMindColors.CyanAccent,
    selectedLeadingIconColor = if (useCoralSelection) SecondMindColors.HeroCoral else SecondMindColors.CyanAccent
)

@Composable
fun galleryTextFieldColors(
    containerColor: Color = SecondMindColors.CardSurface
): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = containerColor,
    unfocusedContainerColor = containerColor,
    disabledContainerColor = containerColor.copy(alpha = 0.6f),
    focusedBorderColor = SecondMindColors.CyanAccent,
    unfocusedBorderColor = SecondMindColors.HairlineBorder,
    focusedTextColor = SecondMindColors.PrimaryInk,
    unfocusedTextColor = SecondMindColors.PrimaryInk,
    focusedPlaceholderColor = SecondMindColors.MutedText,
    unfocusedPlaceholderColor = SecondMindColors.MutedText,
    focusedLeadingIconColor = SecondMindColors.CyanAccent,
    unfocusedLeadingIconColor = SecondMindColors.MutedText
)

/**
 * Pure-white 24dp floating card resting gently on the soft `#FFF5F5` canvas.
 */
@Composable
fun MindSurfaceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = SecondMindColors.CardSurface,
    borderColor: Color = SecondMindColors.HairlineBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    val stroke = if (borderColor == SecondMindColors.HairlineBorder) {
        BorderStroke(0.5.dp, borderColor.copy(alpha = 0.65f))
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

@Composable
fun SelectableOptionCard(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val border = if (selected) SecondMindColors.CyanAccent.copy(alpha = 0.65f) else Color.Transparent
    val bg = if (selected) SecondMindColors.BlueSoftContainer else SecondMindColors.CardElevated
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                contentDescription = if (selected) "$title, selected" else title
            },
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) SecondMindColors.CyanAccent else SecondMindColors.MutedText,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = SecondMindColors.PrimaryInk
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.SecondaryInk
                    )
                }
            }
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    dotColor: Color,
    containerColor: Color = SecondMindColors.CardSurface,
    onClick: (() -> Unit)? = null
) {
    val pillContent: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(50),
            color = containerColor,
            border = BorderStroke(0.75.dp, dotColor.copy(alpha = 0.28f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
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
                    color = SecondMindColors.PrimaryInk,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (onClick != null) {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                },
            contentAlignment = Alignment.Center
        ) {
            pillContent()
        }
    } else {
        pillContent()
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
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onClick)
                        .semantics {
                            role = Role.Button
                            contentDescription = "$value $label"
                        }
                } else {
                    Modifier.semantics { contentDescription = "$value $label" }
                }
            ),
        shape = RoundedCornerShape(20.dp),
        color = SecondMindColors.CardSurface,
        border = BorderStroke(0.5.dp, SecondMindColors.HairlineBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
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

/**
 * Lightweight zero-dependency Markdown renderer for Gemma / offline summaries, Ask AI answers,
 * and Productivity Briefs. Renders crisp headings, **bold**, *italic*, `code`, bullet lists,
 * and editorial-blue `[S1]` / `[S1, S6]` source citations.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = SecondMindColors.PrimaryInk
) {
    val headingColor = SecondMindColors.PrimaryInk
    val citationColor = SecondMindColors.CyanAccent
    val codeBg = SecondMindColors.CardElevated
    val annotated = remember(text, headingColor, citationColor, codeBg) {
        parseSimpleMarkdown(
            raw = text,
            headingColor = headingColor,
            citationColor = citationColor,
            codeBg = codeBg
        )
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color
    )
}

internal fun parseSimpleMarkdown(
    raw: String,
    headingColor: Color = SecondMindColors.PrimaryInk,
    citationColor: Color = SecondMindColors.CyanAccent,
    codeBg: Color = SecondMindColors.CardElevated
): AnnotatedString = buildAnnotatedString {
    val lines = raw.trimEnd().lines()
    lines.forEachIndexed { lineIdx, origLine ->
        val trimmed = origLine.trimStart()
        when {
            trimmed.startsWith("### ") || trimmed.startsWith("## ") || trimmed.startsWith("# ") -> {
                val heading = trimmed.dropWhile { it == '#' }.trim()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = headingColor)) {
                    appendInlineMarkdown(heading, citationColor, codeBg)
                }
            }
            trimmed.startsWith("- [x] ", ignoreCase = true) -> {
                append("✓ ")
                appendInlineMarkdown(trimmed.drop(6), citationColor, codeBg)
            }
            trimmed.startsWith("- [ ] ") -> {
                append("○ ")
                appendInlineMarkdown(trimmed.drop(6), citationColor, codeBg)
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                append("• ")
                appendInlineMarkdown(trimmed.drop(2), citationColor, codeBg)
            }
            else -> {
                appendInlineMarkdown(origLine, citationColor, codeBg)
            }
        }
        if (lineIdx < lines.lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
    line: String,
    citationColor: Color,
    codeBg: Color
) {
    var i = 0
    val n = line.length
    while (i < n) {
        when {
            line.startsWith("**", i) -> {
                val end = line.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineMarkdown(line.substring(i + 2, end), citationColor, codeBg)
                    }
                    i = end + 2
                } else {
                    append("**")
                    i += 2
                }
            }
            line[i] == '`' -> {
                val end = line.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(line.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append('`')
                    i++
                }
            }
            line[i] == '[' && i + 3 < n && (line[i + 1] == 'S' || line[i + 1] == 'M' || line[i + 1] == 'T') -> {
                val end = line.indexOf(']', i + 2)
                val inner = if (end > i + 2 && end - i <= 24) line.substring(i + 1, end) else ""
                val isCitationList = inner.isNotEmpty() && inner.split(',').all { token ->
                    val t = token.trim()
                    t.length in 2..4 && (t[0] == 'S' || t[0] == 'M' || t[0] == 'T') && t.drop(1).all { it.isDigit() }
                }
                if (isCitationList) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = citationColor)) {
                        append(line.substring(i, end + 1))
                    }
                    i = end + 1
                } else {
                    append('[')
                    i++
                }
            }
            line[i] == '*' && (i + 1 < n && line[i + 1] != ' ' && line[i + 1] != '*') -> {
                val end = line.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(line.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append('*')
                    i++
                }
            }
            else -> {
                append(line[i])
                i++
            }
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
    val totalClips = buckets.sumOf { it.segmentCount }
    val totalMems = buckets.sumOf { it.memoryCount }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "7-day capture activity: $totalClips audio clips and $totalMems memories"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("7-Day Capture Activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot("Audio Clips", SecondMindColors.CyanAccent)
                LegendDot("Memories", SecondMindColors.HeroCoral)
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
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(if (isToday) SecondMindColors.CyanAccent else SecondMindColors.CyanAccent.copy(alpha = 0.45f))
                        )
                        Box(
                            modifier = Modifier
                                .width(10.dp)
                                .height((62f * memFrac).coerceAtLeast(if (bucket.memoryCount > 0) 6f else 2f).dp)
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(if (isToday) SecondMindColors.HeroCoral else SecondMindColors.HeroCoral.copy(alpha = 0.45f))
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = bucket.dayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) SecondMindColors.PrimaryInk else SecondMindColors.MutedText,
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
            val prettyName = share.category.name.replace('_', ' ')
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelectCategory(share.category) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "$prettyName: ${share.count} memories, ${(share.ratio * 100).toInt()} percent"
                    }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = prettyName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = SecondMindColors.PrimaryInk
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioRetentionBarCard(
    policy: AudioRetentionPolicy,
    activeAudioChunkCount: Int,
    activeAudioBytes: Long,
    onSelectPolicy: (AudioRetentionPolicy) -> Unit,
    onPurgeExpiredNow: () -> Unit,
    compact: Boolean = false
) {
    MindSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SecondMindColors.BlueSoftContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = SecondMindColors.CyanAccent, modifier = Modifier.size(18.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Audio Auto-Deletion",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SecondMindColors.PrimaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "$activeAudioChunkCount clips • ${formatStorageBytes(activeAudioBytes)} • ${policy.shortLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondMindColors.MutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            SoftPillButton(
                text = "Clean",
                icon = Icons.Default.CleaningServices,
                compact = true,
                onClick = onPurgeExpiredNow
            )
        }

        val chipColors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = SecondMindColors.BlueSoftContainer,
            selectedLabelColor = SecondMindColors.CyanAccent,
            containerColor = SecondMindColors.CardElevated,
            labelColor = SecondMindColors.SecondaryInk
        )

        if (!compact) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                AudioRetentionPolicy.entries.forEach { option ->
                    FilterChip(
                        selected = policy == option,
                        onClick = { onSelectPolicy(option) },
                        colors = chipColors,
                        border = null,
                        shape = RoundedCornerShape(50),
                        label = { Text(option.shortLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    )
                }
            }
        } else {
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
                        colors = chipColors,
                        border = null,
                        shape = RoundedCornerShape(50),
                        label = { Text(option.shortLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    )
                }
            }
            Text(
                text = "Swipe options for 1d–7d & Keep All • Failed/pending clips are never deleted.",
                style = MaterialTheme.typography.labelSmall,
                color = SecondMindColors.MutedText
            )
        }

        if (!compact) {
            val detailCopy = when (policy) {
                AudioRetentionPolicy.AFTER_TRANSCRIPTION ->
                    "Deletes encrypted audio immediately once a non-empty offline transcript is verified saved. Failed, pending, or inaudible clips keep their audio safely for retry."
                AudioRetentionPolicy.KEEP_FOREVER ->
                    "Keeps all encrypted audio chunks on device indefinitely. Use Clean or Save WAV on any clip anytime."
                else ->
                    "Auto-prunes verified transcribed audio older than ${policy.shortLabel} (measured from sync time) while keeping 100% of transcripts, memories, and tasks intact. Failed clips are never auto-deleted."
            }
            Text(
                text = detailCopy,
                style = MaterialTheme.typography.bodySmall,
                color = SecondMindColors.SecondaryInk
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
        containerColor = SecondMindColors.CardSurface,
        title = { Text("Capture to Sunbo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Headline (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Thought, decision, or task...") },
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val dialogChipColors = galleryFilterChipColors(onWhiteCard = true)
                    quickCategories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            colors = dialogChipColors,
                            border = null,
                            shape = RoundedCornerShape(50),
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
                enabled = content.isNotBlank(),
                shape = RoundedCornerShape(50)
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
        containerColor = SecondMindColors.CardSurface,
        title = { Text("Edit Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val dialogChipColors = galleryFilterChipColors(onWhiteCard = true)
                    MemoryCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            colors = dialogChipColors,
                            border = null,
                            shape = RoundedCornerShape(50),
                            label = { Text(cat.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, content, category) },
                enabled = content.isNotBlank(),
                shape = RoundedCornerShape(50)
            ) { Text("Save Changes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun TaskEditorDialog(
    existingTask: TaskReminderEntity? = null,
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String, dueLabel: String?, priority: Int) -> Unit
) {
    var title by remember(existingTask?.taskId) { mutableStateOf(existingTask?.title.orEmpty()) }
    var notes by remember(existingTask?.taskId) { mutableStateOf(existingTask?.notes.orEmpty()) }
    var dueLabel by remember(existingTask?.taskId) { mutableStateOf(existingTask?.dueLabel.orEmpty()) }
    var priority by remember(existingTask?.taskId) { mutableIntStateOf(existingTask?.priority ?: 1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SecondMindColors.CardSurface,
        title = { Text(if (existingTask == null) "New Task" else "Edit Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task title") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dueLabel,
                    onValueChange = { dueLabel = it },
                    label = { Text("Due label (e.g., Tomorrow 5 PM)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Priority", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dialogChipColors = galleryFilterChipColors(onWhiteCard = true)
                    listOf(0 to "Low", 1 to "Normal", 2 to "High").forEach { (prio, label) ->
                        FilterChip(
                            selected = priority == prio,
                            onClick = { priority = prio },
                            colors = dialogChipColors,
                            border = null,
                            shape = RoundedCornerShape(50),
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, notes, dueLabel.ifBlank { null }, priority) },
                enabled = title.isNotBlank(),
                shape = RoundedCornerShape(50)
            ) { Text(if (existingTask == null) "Add Task" else "Save") }
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
    if (bytes < 1024L) return "< 1 KB"
    if (bytes < 1024L * 1024L) {
        return "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
    return "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

fun formatThermalStatusLabel(status: Int): String = when (status) {
    0 -> "Nominal"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    6 -> "Shutdown"
    else -> "Unknown"
}

fun formatHumanEnum(raw: String): String =
    raw.lowercase().split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

fun categoryAccentColor(category: MemoryCategory): Color = when (category) {
    MemoryCategory.TASK, MemoryCategory.COMMITMENT, MemoryCategory.DEADLINE, MemoryCategory.FOLLOW_UP -> SecondMindColors.AmberHighlight
    MemoryCategory.DECISION, MemoryCategory.PROJECT, MemoryCategory.MEETING_NOTE -> SecondMindColors.EmeraldPrivacy
    MemoryCategory.IDEA, MemoryCategory.QUESTION -> SecondMindColors.VioletAi
    else -> SecondMindColors.CyanAccent
}
