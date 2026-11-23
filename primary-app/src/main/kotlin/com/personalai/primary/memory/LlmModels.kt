package com.personalai.primary.memory

import com.personalai.primary.data.db.CertaintyLevel
import com.personalai.primary.data.db.EntityNodeType
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.SummaryType

enum class LlmProviderType(val displayName: String) {
    UOAI_OFFLINE("Universal Offline AI (Gemma 3/4)"),
    LOCAL_HEURISTIC_OFFLINE("Offline Rule Engine (Fallback)")
}

data class ExtractedEntityDraft(
    val name: String,
    val type: EntityNodeType = EntityNodeType.TOPIC
)

data class ExtractedMemoryDraft(
    val category: MemoryCategory,
    val title: String,
    val content: String,
    val confidence: Float,
    val certaintyLevel: CertaintyLevel,
    val entities: List<ExtractedEntityDraft> = emptyList(),
    val dueLabel: String? = null,
    val dueAtMs: Long? = null
)

data class GeneratedSummaryDraft(
    val title: String,
    val markdownBody: String,
    val keyDecisions: List<String>,
    val pendingTasks: List<String>,
    val followUps: List<String>
)

data class GroundedSourceSnippet(
    val citationTag: String,
    val transcriptId: String,
    val memoryId: String? = null,
    val timestampMs: Long,
    val formattedTime: String,
    val category: MemoryCategory? = null,
    val certaintyLevel: CertaintyLevel? = null,
    val text: String
)

data class AskAiAnswer(
    val queryId: String,
    val question: String,
    val answer: String,
    val citedSources: List<GroundedSourceSnippet>,
    val providerUsed: String,
    val grounded: Boolean
)

interface LlmProvider {
    val providerName: String

    suspend fun extractMemories(
        transcriptText: String,
        language: String,
        timestampMs: Long
    ): Result<List<ExtractedMemoryDraft>>

    suspend fun generateSummary(
        summaryType: SummaryType,
        periodLabel: String,
        sources: List<GroundedSourceSnippet>
    ): Result<GeneratedSummaryDraft>

    suspend fun answerGroundedQuestion(
        question: String,
        sources: List<GroundedSourceSnippet>
    ): Result<String>
}
