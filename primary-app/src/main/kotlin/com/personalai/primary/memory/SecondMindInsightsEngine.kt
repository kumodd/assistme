package com.personalai.primary.memory

import com.personalai.primary.data.db.EntityNodeEntity
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.SegmentWithTranscript
import com.personalai.primary.data.db.TaskReminderEntity
import com.personalai.primary.data.db.TaskReminderStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object EntityHygiene {
    val JUNK_NORMALIZED_NAMES: Set<String> = setOf(
        "n/a", "na", "none", "null", "nil", "unknown", "unnamed", "misc", "other", "general",
        "something", "nothing", "someone", "anyone", "everyone", "user", "speaker", "person",
        "topic", "thing", "stuff", "item", "note", "name",
        // Common Hindi/Hinglish non-entity function words mis-extracted by ASR/LLM
        "koi", "kuch", "kuchh", "ham", "hum", "hoga", "hogi", "honge", "jab", "tab", "kab",
        "kya", "hai", "hain", "tha", "thi", "the", "nahi", "nahin", "aur", "ya", "par",
        "mein", "me", "se", "ko", "ka", "ki", "ke", "bhi", "toh", "to", "ab", "abhi",
        "yahan", "wahan", "mera", "meri", "mere", "tum", "aap", "woh", "yeh", "kar", "karna",
        "gaya", "gayi", "raha", "rahi", "jaega", "jayega", "wala", "wali", "ek", "do", "teen"
    )

    fun isValidEntityName(raw: String): Boolean {
        val cleaned = raw.trim()
        if (cleaned.length < 2 || cleaned.length > 48) return false
        val lower = cleaned.lowercase()
        if (lower in JUNK_NORMALIZED_NAMES) return false
        if (lower.all { !it.isLetter() }) return false
        // Reject multi-word strings made solely of junk words
        val tokens = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isNotEmpty() && tokens.all { it in JUNK_NORMALIZED_NAMES }) return false
        return true
    }
}

data class DailyActivityBucket(
    val dayLabel: String,
    val dayStartMs: Long,
    val segmentCount: Int,
    val memoryCount: Int,
    val spokenDurationMs: Long
)

data class CategoryShare(
    val category: MemoryCategory,
    val count: Int,
    val ratio: Float
)

data class LanguageShare(
    val label: String,
    val code: String,
    val count: Int,
    val ratio: Float
)

data class SecondMindInsightsSnapshot(
    val greeting: String = "Your 2nd Mind",
    val todayClipsCount: Int = 0,
    val todaySpokenSeconds: Long = 0L,
    val todayMemoriesCount: Int = 0,
    val totalSpokenSeconds: Long = 0L,
    val captureStreakDays: Int = 0,
    val pinnedCount: Int = 0,
    val openTasksCount: Int = 0,
    val completedTasksCount: Int = 0,
    val taskCompletionRate: Float = 0f,
    val decisionsCount: Int = 0,
    val ideasCount: Int = 0,
    val commitmentsCount: Int = 0,
    val weeklyBuckets: List<DailyActivityBucket> = emptyList(),
    val topCategories: List<CategoryShare> = emptyList(),
    val languageBreakdown: List<LanguageShare> = emptyList(),
    val cleanTopEntities: List<EntityNodeEntity> = emptyList()
)

/**
 * Pure, deterministic, off-main-thread analytics engine powering the "2nd Mind Memory"
 * Home Briefing and Productivity Insights visualizations.
 */
object SecondMindInsightsEngine {

    fun compute(
        segmentsWithTranscripts: List<SegmentWithTranscript>,
        activeMemories: List<MemoryItemEntity>,
        tasks: List<TaskReminderEntity>,
        entities: List<EntityNodeEntity>,
        nowMs: Long = System.currentTimeMillis()
    ): SecondMindInsightsSnapshot {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }

        val todayStartMs = startOfDay(cal)
        val dayMs = 86_400_000L

        // Real spoken audio segments (exclude synthetic manual-note anchors with 0ms duration)
        val spokenSegments = segmentsWithTranscripts.filter { it.segment.durationMs > 0L }
        val todayClips = spokenSegments.filter { it.segment.startTimestampMs >= todayStartMs }
        val todaySpokenSec = todayClips.sumOf { it.segment.durationMs } / 1000L
        val totalSpokenSec = spokenSegments.sumOf { it.segment.durationMs } / 1000L
        val todayMemories = activeMemories.count { it.sourceTimestampMs >= todayStartMs }

        // 7-day buckets (oldest -> today)
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val buckets = (6 downTo 0).map { daysAgo ->
            val dayStart = todayStartMs - (daysAgo * dayMs)
            val dayEnd = dayStart + dayMs - 1L
            val daySegs = spokenSegments.filter { it.segment.startTimestampMs in dayStart..dayEnd }
            val dayMems = activeMemories.count { it.sourceTimestampMs in dayStart..dayEnd }
            DailyActivityBucket(
                dayLabel = if (daysAgo == 0) "Today" else dayFmt.format(Date(dayStart)),
                dayStartMs = dayStart,
                segmentCount = daySegs.size,
                memoryCount = dayMems,
                spokenDurationMs = daySegs.sumOf { it.segment.durationMs }
            )
        }

        // Consecutive active capture streak ending today (or yesterday if nothing captured yet today)
        val activeDayIndices = mutableSetOf<Long>()
        for (item in spokenSegments) {
            activeDayIndices.add(startOfDayMs(item.segment.startTimestampMs))
        }
        for (mem in activeMemories) {
            activeDayIndices.add(startOfDayMs(mem.sourceTimestampMs))
        }
        var streak = 0
        var cursor = todayStartMs
        if (cursor !in activeDayIndices && (cursor - dayMs) in activeDayIndices) {
            cursor -= dayMs
        }
        while (cursor in activeDayIndices) {
            streak++
            cursor -= dayMs
        }

        // Category breakdown
        val memTotal = activeMemories.size.coerceAtLeast(1)
        val categoryShares = activeMemories
            .groupingBy { it.category }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(6)
            .map { (cat, cnt) -> CategoryShare(cat, cnt, cnt.toFloat() / memTotal.toFloat()) }

        // Language distribution from completed non-placeholder transcripts
        val validTranscripts = segmentsWithTranscripts
            .mapNotNull { it.transcript }
            .filter { it.text.isNotBlank() && !it.text.startsWith("[Inaudible") }
        val trTotal = validTranscripts.size.coerceAtLeast(1)
        val langCounts = mutableMapOf<String, Int>()
        for (tr in validTranscripts) {
            val code = when {
                tr.language == "hi-en" -> "hi-en"
                tr.language.startsWith("hi") -> "hi"
                else -> "en"
            }
            langCounts[code] = (langCounts[code] ?: 0) + 1
        }
        val langShares = langCounts.entries
            .sortedByDescending { it.value }
            .map { (code, cnt) ->
                val label = when (code) {
                    "hi-en" -> "Hinglish"
                    "hi" -> "Hindi"
                    else -> "English"
                }
                LanguageShare(label, code, cnt, cnt.toFloat() / trTotal.toFloat())
            }

        val openTasks = tasks.count { it.status == TaskReminderStatus.OPEN }
        val doneTasks = tasks.count { it.status == TaskReminderStatus.DONE }
        val taskDenom = (openTasks + doneTasks).coerceAtLeast(1)

        val cleanEntities = entities.filter { EntityHygiene.isValidEntityName(it.name) }

        return SecondMindInsightsSnapshot(
            greeting = greeting,
            todayClipsCount = todayClips.size,
            todaySpokenSeconds = todaySpokenSec,
            todayMemoriesCount = todayMemories,
            totalSpokenSeconds = totalSpokenSec,
            captureStreakDays = streak,
            pinnedCount = activeMemories.count { it.isPinned },
            openTasksCount = openTasks,
            completedTasksCount = doneTasks,
            taskCompletionRate = if (openTasks + doneTasks == 0) 0f else doneTasks.toFloat() / taskDenom.toFloat(),
            decisionsCount = activeMemories.count { it.category == MemoryCategory.DECISION },
            ideasCount = activeMemories.count { it.category == MemoryCategory.IDEA },
            commitmentsCount = activeMemories.count {
                it.category == MemoryCategory.COMMITMENT || it.category == MemoryCategory.FOLLOW_UP || it.category == MemoryCategory.DEADLINE
            },
            weeklyBuckets = buckets,
            topCategories = categoryShares,
            languageBreakdown = langShares,
            cleanTopEntities = cleanEntities
        )
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfDayMs(tsMs: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = tsMs }
        return startOfDay(c)
    }
}
