package com.personalai.primary.memory

import com.personalai.primary.data.db.CertaintyLevel
import com.personalai.primary.data.db.EntityNodeType
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.SummaryType
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic zero-network extractor, summarizer, and grounded Q&A engine supporting
 * English, Hindi, and Hinglish. Always available offline and used whenever cloud AI is disabled
 * or an API key is not configured.
 */
@Singleton
class LocalHeuristicLlmProvider @Inject constructor() : LlmProvider {

    override val providerName: String = "Offline Local Memory Engine"

    private val commitmentMarkers = listOf("i promise", "i will ", "i'll ", "promised", "commit", "वादा", "करूंगा", "करूँगा", "करना है")
    private val taskMarkers = listOf("need to ", "have to ", "todo", "to-do", "remind me", "don't forget", "send ", "call ", "email ", "prepare ", "finish ", "याद दिला", "भेजना", "कॉल करना")
    private val deadlineMarkers = listOf("deadline", "due ", "by tomorrow", "by friday", "by monday", "by tonight", "next week", "कल तक", "तारीख", "अंतिम तिथि")
    private val decisionMarkers = listOf("decided", "we agreed", "let's go with", "finalized", "approved", "तय किया", "फैसला")
    private val preferenceMarkers = listOf("i prefer", "i like ", "i love ", "i hate ", "my favorite", "मुझे पसंद", "पसंद नहीं")
    private val ideaMarkers = listOf("idea", "startup", "business", "what if we", "brainstorm", "feature", "विचार", "बिजनेस")
    private val meetingMarkers = listOf("meeting", "client", "standup", "review call", "agenda", "मीटिंग", "क्लाइंट")
    private val projectMarkers = listOf("project", "milestone", "release", "roadmap", "product", "प्रोजेक्ट", "ऐप")
    private val followUpMarkers = listOf("follow up", "follow-up", "check back", "circle back", "ping ", "फॉलो अप")
    private val eventMarkers = listOf("appointment", "flight", "dinner", "birthday", "conference", "workshop", "कार्यक्रम", "शादी")

    private val stopCapitalizedWords = setOf(
        "I", "The", "This", "That", "We", "They", "He", "She", "It", "Yes", "No",
        "Okay", "Ok", "Well", "So", "And", "But", "Today", "Tomorrow", "Yesterday",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        // Common Hindi / Hinglish words capitalized mid-sentence by on-device ASR
        "Hai", "Hain", "Naam", "Din", "Jaisi", "Jaise", "Tak", "Uske", "Sal", "Kitna",
        "Har", "Kya", "Kiya", "Mein", "Thi", "Tha", "Laga", "Gaya", "Bhi", "Aati",
        "Hui", "Hui Thi", "Pahle", "Ban", "Bani", "Gai", "Gaye", "Raha", "Rahi",
        "Sawal", "Jawab", "Piche", "Puri", "Tarah", "Baat", "Baje", "Raat", "Subah",
        "Shuru", "Lekin", "Kyunki", "Agar", "Chalti", "Pass", "Insan", "Sakta", "Sakti",
        "Dekh", "Jiski", "Koi", "Kuch", "Abhi", "Wali", "Wala", "Vahi", "Usne", "Aag",
        "Katkar", "Aisi", "Usi", "Kareeb", "Pata", "Roj", "Yad", "Aksar", "Sidha"
    )

    override suspend fun extractMemories(
        transcriptText: String,
        language: String,
        timestampMs: Long
    ): Result<List<ExtractedMemoryDraft>> {
        val cleaned = transcriptText.trim()
        if (cleaned.length < 8 || cleaned.startsWith("[Inaudible")) {
            return Result.success(emptyList())
        }

        val sentences = cleaned.split(Regex("(?<=[.!?।])\\s+"))
            .map { it.trim() }
            .filter { it.length >= 6 }
            .ifEmpty { listOf(cleaned) }

        val drafts = mutableListOf<ExtractedMemoryDraft>()
        val extractedEntities = extractEntities(cleaned)

        for (sentence in sentences) {
            val lower = sentence.lowercase()
            val (dueLabel, dueAtMs) = inferDueTime(lower, timestampMs)

            val matchedCategory: Pair<MemoryCategory, Float>? = when {
                deadlineMarkers.any { lower.contains(it) } -> MemoryCategory.DEADLINE to 0.80f
                commitmentMarkers.any { lower.contains(it) } -> MemoryCategory.COMMITMENT to 0.78f
                followUpMarkers.any { lower.contains(it) } -> MemoryCategory.FOLLOW_UP to 0.76f
                taskMarkers.any { lower.contains(it) } -> MemoryCategory.TASK to 0.75f
                decisionMarkers.any { lower.contains(it) } -> MemoryCategory.DECISION to 0.78f
                preferenceMarkers.any { lower.contains(it) } -> MemoryCategory.PREFERENCE to 0.74f
                ideaMarkers.any { lower.contains(it) } -> MemoryCategory.IDEA to 0.74f
                meetingMarkers.any { lower.contains(it) } -> MemoryCategory.MEETING_NOTE to 0.72f
                projectMarkers.any { lower.contains(it) } -> MemoryCategory.PROJECT to 0.72f
                eventMarkers.any { lower.contains(it) } -> MemoryCategory.EVENT to 0.72f
                sentence.endsWith("?") || lower.startsWith("why ") || lower.startsWith("how ") || lower.startsWith("when ") ->
                    MemoryCategory.QUESTION to 0.70f
                else -> null
            }

            if (matchedCategory != null) {
                val (cat, conf) = matchedCategory
                drafts.add(
                    ExtractedMemoryDraft(
                        category = cat,
                        title = buildConciseTitle(cat, sentence),
                        content = sentence,
                        confidence = conf,
                        certaintyLevel = if (conf >= 0.76f) CertaintyLevel.INFERRED_MEDIUM else CertaintyLevel.UNCERTAIN,
                        entities = extractedEntities,
                        dueLabel = dueLabel,
                        dueAtMs = dueAtMs
                    )
                )
            }
        }

        // If no specialized category triggered but the utterance is substantive, store as a low-certainty FACT note.
        if (drafts.isEmpty() && cleaned.split(' ').size >= 5) {
            drafts.add(
                ExtractedMemoryDraft(
                    category = MemoryCategory.FACT,
                    title = buildConciseTitle(MemoryCategory.FACT, cleaned),
                    content = cleaned,
                    confidence = 0.58f,
                    certaintyLevel = CertaintyLevel.UNCERTAIN,
                    entities = extractedEntities
                )
            )
        }

        return Result.success(drafts.distinctBy { it.category to it.content }.take(6))
    }

    override suspend fun generateSummary(
        summaryType: SummaryType,
        periodLabel: String,
        sources: List<GroundedSourceSnippet>
    ): Result<GeneratedSummaryDraft> {
        if (sources.isEmpty()) {
            return Result.failure(IllegalStateException("No transcripts available for $periodLabel"))
        }

        val decisions = sources.filter { it.category == MemoryCategory.DECISION }.map { it.text }.distinct()
        val tasks = sources.filter {
            it.category == MemoryCategory.TASK ||
                it.category == MemoryCategory.COMMITMENT ||
                it.category == MemoryCategory.DEADLINE
        }.map { it.text }.distinct()
        val followUps = sources.filter { it.category == MemoryCategory.FOLLOW_UP }.map { it.text }.distinct()
        val transcriptLines = sources.filter { it.memoryId == null }.ifEmpty { sources }

        val md = buildString {
            appendLine("## ${summaryType.name.lowercase().replaceFirstChar { it.uppercase() }} Summary — $periodLabel")
            appendLine()
            appendLine("Grounded in **${transcriptLines.size}** recorded snippet(s):")
            appendLine()
            transcriptLines.take(12).forEach { s ->
                appendLine("- **[${s.citationTag} • ${s.formattedTime}]** ${s.text}")
            }
            if (decisions.isNotEmpty()) {
                appendLine()
                appendLine("### Key Decisions")
                decisions.take(8).forEach { appendLine("- $it") }
            }
            if (tasks.isNotEmpty()) {
                appendLine()
                appendLine("### Pending Tasks & Commitments")
                tasks.take(10).forEach { appendLine("- [ ] $it") }
            }
            if (followUps.isNotEmpty()) {
                appendLine()
                appendLine("### Follow-Ups")
                followUps.take(8).forEach { appendLine("- $it") }
            }
        }

        return Result.success(
            GeneratedSummaryDraft(
                title = "${summaryType.name.lowercase().replaceFirstChar { it.uppercase() }} Summary ($periodLabel)",
                markdownBody = md.trim(),
                keyDecisions = decisions,
                pendingTasks = tasks,
                followUps = followUps
            )
        )
    }

    override suspend fun answerGroundedQuestion(
        question: String,
        sources: List<GroundedSourceSnippet>
    ): Result<String> {
        if (sources.isEmpty()) {
            return Result.success(
                "I couldn't find any recorded conversations or saved memories matching \"$question\". No facts were invented."
            )
        }

        val answer = buildString {
            appendLine("Based on your recorded conversations and memories:")
            appendLine()
            sources.take(6).forEach { src ->
                val certaintySuffix = when (src.certaintyLevel) {
                    CertaintyLevel.UNCERTAIN -> " *(uncertain extraction)*"
                    CertaintyLevel.INFERRED_MEDIUM -> " *(inferred)*"
                    else -> ""
                }
                val catPrefix = src.category?.let { "[${it.name}] " } ?: ""
                appendLine("- **[${src.citationTag} | ${src.formattedTime}]** $catPrefix${src.text}$certaintySuffix")
            }
            appendLine()
            append("Tap any cited source below to inspect the original transcript and audio.")
        }
        return Result.success(answer)
    }

    private fun extractEntities(text: String): List<ExtractedEntityDraft> {
        val entities = mutableListOf<ExtractedEntityDraft>()
        val nameRegex = Regex("\\b([A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]{2,})?)\\b")
        for (match in nameRegex.findAll(text)) {
            val candidate = match.groupValues[1].trim()
            if (candidate !in stopCapitalizedWords &&
                EntityHygiene.isValidEntityName(candidate) &&
                !candidate.all { it.isUpperCase() }
            ) {
                val type = when {
                    text.lowercase().contains("project ${candidate.lowercase()}") -> EntityNodeType.PROJECT
                    else -> EntityNodeType.PERSON
                }
                entities.add(ExtractedEntityDraft(candidate, type))
            }
        }
        return entities.distinctBy { it.name.lowercase() }.take(6)
    }

    private fun inferDueTime(lowerText: String, baseTimestampMs: Long): Pair<String?, Long?> {
        val cal = Calendar.getInstance().apply { timeInMillis = baseTimestampMs }
        return when {
            lowerText.contains("tomorrow") || lowerText.contains("कल") -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 10)
                cal.set(Calendar.MINUTE, 0)
                "Tomorrow 10:00" to cal.timeInMillis
            }
            lowerText.contains("tonight") || lowerText.contains("आज शाम") -> {
                cal.set(Calendar.HOUR_OF_DAY, 20)
                cal.set(Calendar.MINUTE, 0)
                "Tonight 20:00" to cal.timeInMillis
            }
            lowerText.contains("next week") || lowerText.contains("अगले हफ्ते") -> {
                cal.add(Calendar.DAY_OF_YEAR, 7)
                cal.set(Calendar.HOUR_OF_DAY, 10)
                cal.set(Calendar.MINUTE, 0)
                "Next week" to cal.timeInMillis
            }
            lowerText.contains("friday") || lowerText.contains("शुक्रवार") -> {
                val daysUntilFri = ((Calendar.FRIDAY - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7).let { if (it == 0) 7 else it }
                cal.add(Calendar.DAY_OF_YEAR, daysUntilFri)
                cal.set(Calendar.HOUR_OF_DAY, 10)
                cal.set(Calendar.MINUTE, 0)
                "Friday" to cal.timeInMillis
            }
            else -> null to null
        }
    }

    private fun buildConciseTitle(category: MemoryCategory, sentence: String): String {
        val trimmed = sentence.trim().replace(Regex("\\s+"), " ")
        return if (trimmed.length <= 64) trimmed else trimmed.take(61) + "..."
    }
}
