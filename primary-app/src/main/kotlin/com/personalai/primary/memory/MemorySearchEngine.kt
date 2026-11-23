package com.personalai.primary.memory

import com.personalai.primary.data.db.EmbeddingOwnerType
import com.personalai.primary.data.db.EmbeddingVectorEntity
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryDao
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.TranscriptDao
import com.personalai.primary.data.db.TranscriptEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SearchFilter(
    val category: MemoryCategory? = null,
    val startMs: Long? = null,
    val endMs: Long? = null
)

data class ScoredMemoryResult(
    val memory: MemoryItemEntity,
    val score: Float
)

data class TemporalWindow(
    val startMs: Long,
    val endMs: Long,
    val label: String
)

@Singleton
class MemorySearchEngine @Inject constructor(
    private val transcriptDao: TranscriptDao,
    private val memoryDao: MemoryDao
) {
    private val timeFormatter = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())

    // In-memory vector cache avoids repeatedly querying & deserializing SQLite BLOBs on every keystroke/query.
    private val vectorCache = ConcurrentHashMap<Pair<EmbeddingOwnerType, String>, FloatArray>()
    @Volatile
    private var cacheWarmed: Boolean = false

    private suspend fun ensureVectorCacheWarmed() {
        if (cacheWarmed) return
        val rows = memoryDao.getEmbeddingsByModel(LocalSemanticEmbedder.MODEL_ID)
        for (row in rows) {
            vectorCache[row.ownerType to row.ownerId] = LocalSemanticEmbedder.fromByteArray(row.vectorBlob)
        }
        cacheWarmed = true
    }

    fun evictMemoryVector(memoryId: String) {
        vectorCache.remove(EmbeddingOwnerType.MEMORY to memoryId)
    }

    fun clearVectorCache() {
        vectorCache.clear()
        cacheWarmed = false
    }

    suspend fun indexTranscript(transcript: TranscriptEntity) {
        val vector = LocalSemanticEmbedder.embed(transcript.text)
        vectorCache[EmbeddingOwnerType.TRANSCRIPT to transcript.transcriptId] = vector
        memoryDao.upsertEmbedding(
            EmbeddingVectorEntity(
                vectorId = UUID.randomUUID().toString(),
                ownerType = EmbeddingOwnerType.TRANSCRIPT,
                ownerId = transcript.transcriptId,
                modelId = LocalSemanticEmbedder.MODEL_ID,
                dimensions = LocalSemanticEmbedder.DIMENSIONS,
                vectorBlob = LocalSemanticEmbedder.toByteArray(vector),
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun indexMemory(memory: MemoryItemEntity) {
        val text = "${memory.category.name} ${memory.title} ${memory.content} ${memory.entitiesCsv}"
        val vector = LocalSemanticEmbedder.embed(text)
        vectorCache[EmbeddingOwnerType.MEMORY to memory.memoryId] = vector
        memoryDao.upsertEmbedding(
            EmbeddingVectorEntity(
                vectorId = UUID.randomUUID().toString(),
                ownerType = EmbeddingOwnerType.MEMORY,
                ownerId = memory.memoryId,
                modelId = LocalSemanticEmbedder.MODEL_ID,
                dimensions = LocalSemanticEmbedder.DIMENSIONS,
                vectorBlob = LocalSemanticEmbedder.toByteArray(vector),
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun searchMemoriesHybrid(
        query: String,
        filter: SearchFilter = SearchFilter(),
        limit: Int = 40
    ): List<ScoredMemoryResult> {
        val all = if (filter.startMs != null && filter.endMs != null) {
            memoryDao.getByDateRange(filter.startMs, filter.endMs)
        } else {
            memoryDao.getActiveMemories(500)
        }.filter { m -> filter.category == null || m.category == filter.category }

        val q = query.trim()
        if (q.isEmpty()) {
            return all.take(limit).map { ScoredMemoryResult(it, 1.0f) }
        }

        ensureVectorCacheWarmed()
        val queryVec = LocalSemanticEmbedder.embed(q)

        val queryTokens = tokenizeForBoost(q)
        return all.mapNotNull { mem ->
            val rawText = "${mem.title} ${mem.content} ${mem.entitiesCsv}".lowercase()
            val keywordHits = queryTokens.count { tok -> rawText.contains(tok) }
            val keywordScore = if (queryTokens.isEmpty()) 0f else keywordHits.toFloat() / queryTokens.size
            val storedVec = vectorCache[EmbeddingOwnerType.MEMORY to mem.memoryId]
                ?: LocalSemanticEmbedder.embed("${mem.category.name} ${mem.title} ${mem.content} ${mem.entitiesCsv}")
                    .also { vectorCache[EmbeddingOwnerType.MEMORY to mem.memoryId] = it }
            val semScore = LocalSemanticEmbedder.cosineSimilarity(queryVec, storedVec).coerceAtLeast(0f)
            val pinBoost = if (mem.isPinned) 0.05f else 0f
            val combined = (keywordScore * 0.65f) + (semScore * 0.35f) + pinBoost
            if (combined >= 0.14f || keywordHits > 0) {
                ScoredMemoryResult(mem, combined)
            } else {
                null
            }
        }
            .sortedByDescending { it.score }
            .take(limit)
    }

    suspend fun retrieveGroundedContextForQuestion(
        question: String,
        nowMs: Long = System.currentTimeMillis(),
        maxSources: Int = 8
    ): List<GroundedSourceSnippet> {
        val temporal = parseTemporalWindow(question, nowMs)
        val boostedCategories = detectIntentCategories(question)
        val queryVec = LocalSemanticEmbedder.embed(question)
        val queryTokens = tokenizeForBoost(question)

        val candidateTranscripts = if (temporal != null) {
            transcriptDao.getByDateRange(temporal.startMs, temporal.endMs)
        } else {
            transcriptDao.getRecentTranscripts(200)
        }

        val candidateMemories = if (temporal != null) {
            memoryDao.getByDateRange(temporal.startMs, temporal.endMs)
        } else {
            memoryDao.getActiveMemories(300)
        }

        if (candidateTranscripts.isEmpty() && candidateMemories.isEmpty()) {
            return emptyList()
        }

        ensureVectorCacheWarmed()

        // FTS4 + substring matches for strong lexical grounding
        val ftsMatchedIds = mutableSetOf<String>()
        val ftsQuery = buildSafeFtsQuery(queryTokens)
        if (ftsQuery.isNotEmpty()) {
            runCatching { transcriptDao.searchFts(ftsQuery) }.getOrDefault(emptyList())
                .forEach { ftsMatchedIds.add(it.transcriptId) }
        }
        for (tok in queryTokens.take(4)) {
            transcriptDao.searchSubstring(tok).forEach { ftsMatchedIds.add(it.transcriptId) }
        }

        data class RankedSnippet(
            val score: Float,
            val transcriptId: String,
            val memoryId: String?,
            val timestampMs: Long,
            val category: MemoryCategory?,
            val certainty: com.personalai.primary.data.db.CertaintyLevel?,
            val text: String
        )

        val ranked = mutableListOf<RankedSnippet>()
        val isBroadTemporalAsk = temporal != null && isGenericSummaryOrRecallQuestion(question)

        for (mem in candidateMemories) {
            val lower = "${mem.title} ${mem.content} ${mem.entitiesCsv}".lowercase()
            val tokenRatio = if (queryTokens.isEmpty()) 0f else queryTokens.count { lower.contains(it) }.toFloat() / queryTokens.size
            val vec = vectorCache[EmbeddingOwnerType.MEMORY to mem.memoryId]
                ?: LocalSemanticEmbedder.embed("${mem.category.name} ${mem.title} ${mem.content} ${mem.entitiesCsv}")
                    .also { vectorCache[EmbeddingOwnerType.MEMORY to mem.memoryId] = it }
            val sem = LocalSemanticEmbedder.cosineSimilarity(queryVec, vec).coerceAtLeast(0f)
            val catBoost = if (mem.category in boostedCategories) 0.28f else 0f
            val temporalBoost = if (isBroadTemporalAsk) 0.25f else 0f
            val totalScore = (tokenRatio * 0.55f) + (sem * 0.35f) + catBoost + temporalBoost

            if (totalScore >= 0.22f || (mem.category in boostedCategories && (tokenRatio > 0f || isBroadTemporalAsk || boostedCategories.isNotEmpty()))) {
                ranked.add(
                    RankedSnippet(
                        score = totalScore,
                        transcriptId = mem.sourceTranscriptId,
                        memoryId = mem.memoryId,
                        timestampMs = mem.sourceTimestampMs,
                        category = mem.category,
                        certainty = mem.certaintyLevel,
                        text = mem.content
                    )
                )
            }
        }

        for (tr in candidateTranscripts) {
            val lower = tr.text.lowercase()
            val tokenRatio = if (queryTokens.isEmpty()) 0f else queryTokens.count { lower.contains(it) }.toFloat() / queryTokens.size
            val ftsBoost = if (tr.transcriptId in ftsMatchedIds) 0.30f else 0f
            val vec = vectorCache[EmbeddingOwnerType.TRANSCRIPT to tr.transcriptId]
                ?: LocalSemanticEmbedder.embed(tr.text)
                    .also { vectorCache[EmbeddingOwnerType.TRANSCRIPT to tr.transcriptId] = it }
            val sem = LocalSemanticEmbedder.cosineSimilarity(queryVec, vec).coerceAtLeast(0f)
            val temporalBoost = if (isBroadTemporalAsk) 0.35f else 0f
            val totalScore = (tokenRatio * 0.45f) + (sem * 0.35f) + ftsBoost + temporalBoost

            if (totalScore >= 0.22f) {
                ranked.add(
                    RankedSnippet(
                        score = totalScore,
                        transcriptId = tr.transcriptId,
                        memoryId = null,
                        timestampMs = tr.startTimeMs,
                        category = null,
                        certainty = null,
                        text = tr.text
                    )
                )
            }
        }

        val deduped = ranked
            .sortedByDescending { it.score }
            .distinctBy { it.transcriptId to it.text.trim().lowercase().take(64) }
            .take(maxSources)

        return deduped.mapIndexed { idx, item ->
            GroundedSourceSnippet(
                citationTag = "S${idx + 1}",
                transcriptId = item.transcriptId,
                memoryId = item.memoryId,
                timestampMs = item.timestampMs,
                formattedTime = formatTimestamp(item.timestampMs),
                category = item.category,
                certaintyLevel = item.certainty,
                text = item.text
            )
        }
    }

    fun parseTemporalWindow(question: String, nowMs: Long = System.currentTimeMillis()): TemporalWindow? {
        val lower = question.lowercase()
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun endOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        return when {
            lower.contains("yesterday") || lower.contains("बीते कल") -> {
                val y = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
                TemporalWindow(startOfDay(y), endOfDay(y), "Yesterday")
            }
            lower.contains("today") || lower.contains("आज") -> {
                TemporalWindow(startOfDay(cal), endOfDay(cal), "Today")
            }
            lower.contains("this week") || lower.contains("इस हफ्ते") -> {
                val start = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -6) }
                TemporalWindow(startOfDay(start), endOfDay(cal), "This Week")
            }
            lower.contains("last week") || lower.contains("पिछले हफ्ते") -> {
                val end = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
                val start = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -13) }
                TemporalWindow(startOfDay(start), endOfDay(end), "Last Week")
            }
            else -> null
        }
    }

    fun formatTimestamp(tsMs: Long): String = synchronized(timeFormatter) {
        timeFormatter.format(Date(tsMs))
    }

    private fun detectIntentCategories(question: String): Set<MemoryCategory> {
        val lower = question.lowercase()
        return buildSet {
            if (lower.contains("promise") || lower.contains("commit") || lower.contains("वादा")) {
                add(MemoryCategory.COMMITMENT)
                add(MemoryCategory.TASK)
                add(MemoryCategory.FOLLOW_UP)
            }
            if (lower.contains("task") || lower.contains("todo") || lower.contains("to do") || lower.contains("करना")) {
                add(MemoryCategory.TASK)
                add(MemoryCategory.COMMITMENT)
            }
            if (lower.contains("deadline") || lower.contains("due") || lower.contains("upcoming")) {
                add(MemoryCategory.DEADLINE)
                add(MemoryCategory.TASK)
                add(MemoryCategory.EVENT)
            }
            if (lower.contains("idea") || lower.contains("business") || lower.contains("विचार")) {
                add(MemoryCategory.IDEA)
            }
            if (lower.contains("decid") || lower.contains("decision") || lower.contains("फैसला")) {
                add(MemoryCategory.DECISION)
            }
            if (lower.contains("project") || lower.contains("प्रोजेक्ट")) {
                add(MemoryCategory.PROJECT)
            }
            if (lower.contains("meeting") || lower.contains("मीटिंग")) {
                add(MemoryCategory.MEETING_NOTE)
            }
            if (lower.contains("prefer") || lower.contains("like")) {
                add(MemoryCategory.PREFERENCE)
            }
        }
    }

    private fun isGenericSummaryOrRecallQuestion(question: String): Boolean {
        val lower = question.lowercase()
        return lower.contains("what did i discuss") ||
            lower.contains("summarize") ||
            lower.contains("summary") ||
            lower.contains("what happened") ||
            lower.contains("क्या बात")
    }

    private fun tokenizeForBoost(raw: String): List<String> {
        val stop = setOf(
            "what", "did", "when", "where", "who", "how", "why", "the", "this", "that", "about",
            "with", "from", "have", "were", "was", "are", "for", "and", "any", "anyone", "mention",
            "discuss", "yesterday", "today", "week", "last", "coming", "summarize", "conversations", "my",
            "to", "in", "on", "at", "of", "is", "me", "it", "do", "am", "be", "we", "he", "or",
            "so", "up", "no", "if", "an", "as", "by"
        )
        return raw.lowercase()
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .filter { it.length >= 2 && it !in stop }
            .distinct()
    }

    private fun buildSafeFtsQuery(tokens: List<String>): String {
        val asciiTokens = tokens.filter { it.all { ch -> ch in 'a'..'z' || ch in '0'..'9' } }.take(5)
        if (asciiTokens.isEmpty()) return ""
        return asciiTokens.joinToString(" OR ") { "$it*" }
    }
}
