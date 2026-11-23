package com.personalai.primary.memory.uoai

import com.personalai.primary.data.db.CertaintyLevel
import com.personalai.primary.data.db.EntityNodeType
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.SummaryType
import com.personalai.primary.memory.EntityHygiene
import com.personalai.primary.memory.ExtractedEntityDraft
import com.personalai.primary.memory.ExtractedMemoryDraft
import com.personalai.primary.memory.GeneratedSummaryDraft
import com.personalai.primary.memory.GroundedSourceSnippet
import com.personalai.primary.memory.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class UoaiExtractionWrapper(
    val memories: List<UoaiMemoryJson> = emptyList()
)

@Serializable
private data class UoaiMemoryJson(
    val category: String = "FACT",
    val title: String = "",
    val content: String = "",
    val confidence: Float = 0.75f,
    val certainty: String = "INFERRED_MEDIUM",
    val entities: List<String> = emptyList(),
    val dueLabel: String? = null
)

@Serializable
private data class UoaiSummaryWrapper(
    val title: String = "",
    val markdownBody: String = "",
    val keyDecisions: List<String> = emptyList(),
    val pendingTasks: List<String> = emptyList(),
    val followUps: List<String> = emptyList()
)

/**
 * 100% offline on-device Gemma 3 / Gemma 4 `LlmProvider` backed by Universal Offline AI Provider
 * (`com.uoai.provider` over localhost loopback `127.0.0.1:8765/v1`).
 */
@Singleton
class UoaiLlmProvider @Inject constructor(
    private val uoaiClient: UoaiClient
) : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override val providerName: String
        get() {
            val model = uoaiClient.cachedActiveModel?.name?.takeIf { it.isNotBlank() }
            return if (model != null) "UOAI Offline ($model)" else "Universal Offline AI (Gemma 3/4)"
        }

    override suspend fun extractMemories(
        transcriptText: String,
        language: String,
        timestampMs: Long
    ): Result<List<ExtractedMemoryDraft>> = runCatching {
        val systemPrompt = """
            You are an offline personal AI memory extractor for spoken transcripts (English, Hindi, or Hinglish).
            Extract only information explicitly or plausibly stated in the transcript. NEVER invent facts.
            Respond ONLY with valid JSON (no markdown commentary):
            {"memories":[{"category":"FACT|PREFERENCE|DECISION|COMMITMENT|TASK|DEADLINE|EVENT|IDEA|QUESTION|ENTITY|PROJECT|MEETING_NOTE|FOLLOW_UP","title":"short title","content":"concise memory","confidence":0.0,"certainty":"EXPLICIT|INFERRED_MEDIUM|UNCERTAIN","entities":["Name"],"dueLabel":null}]}
            If nothing meaningful exists, return {"memories":[]}.
        """.trimIndent()

        val raw = uoaiClient.chatCompletion(
            messages = listOf(
                UoaiChatMessage("system", systemPrompt),
                UoaiChatMessage("user", "Language=$language\nTranscript:\n$transcriptText")
            ),
            temperature = 0.1f,
            requestedMaxTokens = 1_024
        )
        val clean = UoaiProtocolHelpers.extractJsonObject(raw)
        val parsed = json.decodeFromString<UoaiExtractionWrapper>(clean)
        parsed.memories.filter { it.content.isNotBlank() }.map { item ->
            val cat = runCatching { MemoryCategory.valueOf(item.category.trim().uppercase()) }
                .getOrDefault(MemoryCategory.FACT)
            val cert = runCatching { CertaintyLevel.valueOf(item.certainty.trim().uppercase()) }
                .getOrDefault(if (item.confidence >= 0.85f) CertaintyLevel.EXPLICIT else CertaintyLevel.INFERRED_MEDIUM)
            ExtractedMemoryDraft(
                category = cat,
                title = item.title.ifBlank { item.content.take(64) },
                content = item.content.trim(),
                confidence = item.confidence.coerceIn(0.1f, 1.0f),
                certaintyLevel = cert,
                entities = item.entities
                    .map { it.trim() }
                    .filter { EntityHygiene.isValidEntityName(it) }
                    .distinctBy { it.lowercase() }
                    .map { ExtractedEntityDraft(it, EntityNodeType.TOPIC) },
                dueLabel = item.dueLabel?.takeIf { it.isNotBlank() }
            )
        }
    }

    override suspend fun generateSummary(
        summaryType: SummaryType,
        periodLabel: String,
        sources: List<GroundedSourceSnippet>
    ): Result<GeneratedSummaryDraft> = runCatching {
        val maxContextChars = computeSourceCharBudget()
        val context = buildBoundedSourceContext(sources, maxContextChars)
        val systemPrompt = """
            Generate a grounded ${summaryType.name} summary for "$periodLabel" strictly from the provided transcripts and memories.
            Do NOT invent events, tasks, or decisions not present in sources. Cite sources inline like [S1].
            Respond ONLY with valid JSON:
            {"title":"...","markdownBody":"...","keyDecisions":["..."],"pendingTasks":["..."],"followUps":["..."]}
        """.trimIndent()

        val raw = uoaiClient.chatCompletion(
            messages = listOf(
                UoaiChatMessage("system", systemPrompt),
                UoaiChatMessage("user", context)
            ),
            temperature = 0.15f,
            requestedMaxTokens = 1_536
        )
        val clean = UoaiProtocolHelpers.extractJsonObject(raw)
        val parsed = runCatching { json.decodeFromString<UoaiSummaryWrapper>(clean) }.getOrNull()
        if (parsed != null && parsed.markdownBody.isNotBlank()) {
            GeneratedSummaryDraft(
                title = parsed.title.ifBlank { "${summaryType.name} Summary ($periodLabel)" },
                markdownBody = parsed.markdownBody.trim(),
                keyDecisions = parsed.keyDecisions,
                pendingTasks = parsed.pendingTasks,
                followUps = parsed.followUps
            )
        } else {
            // If Gemma returned readable Markdown directly instead of strict JSON, preserve it cleanly.
            GeneratedSummaryDraft(
                title = "${summaryType.name} Summary ($periodLabel)",
                markdownBody = raw.trim(),
                keyDecisions = emptyList(),
                pendingTasks = emptyList(),
                followUps = emptyList()
            )
        }
    }

    override suspend fun answerGroundedQuestion(
        question: String,
        sources: List<GroundedSourceSnippet>
    ): Result<String> = runCatching {
        val messages = buildGroundedQaMessages(question, sources)
        uoaiClient.chatCompletion(
            messages = messages,
            temperature = 0.15f,
            requestedMaxTokens = 1_536
        )
    }

    fun streamGroundedQuestion(
        question: String,
        sources: List<GroundedSourceSnippet>
    ): Flow<String> {
        val messages = buildGroundedQaMessages(question, sources)
        return uoaiClient.chatCompletionStream(
            messages = messages,
            temperature = 0.15f,
            requestedMaxTokens = 1_536
        )
    }

    fun supportsStreaming(): Boolean {
        return uoaiClient.cachedCapabilities?.supportsStreaming ?: true
    }

    suspend fun generateProductivityBrief(
        statsContext: String,
        sources: List<GroundedSourceSnippet>
    ): Result<String> = runCatching {
        val bounded = buildBoundedSourceContext(sources, computeSourceCharBudget() / 2)
        val systemPrompt = """
            You are a personal 2nd Mind productivity coach running 100% offline.
            Given the user's recent memories, tasks, and metrics below, write a crisp, actionable Productivity Brief with 3 short bullet sections:
            1. **Focus Themes** (what occupied the user's mind recently)
            2. **Priority Next Actions** (unfinished tasks/commitments strictly from the notes)
            3. **Quick 2nd-Mind Nudge** (1 concise suggestion)
            Rules: NEVER invent events, names, or tasks not listed. Cite [S#] where relevant. Keep it under 160 words.
        """.trimIndent()
        uoaiClient.chatCompletion(
            messages = listOf(
                UoaiChatMessage("system", systemPrompt),
                UoaiChatMessage("user", "$statsContext\n\nRecent Memories & Tasks:\n$bounded")
            ),
            temperature = 0.2f,
            requestedMaxTokens = 512
        )
    }

    private fun buildGroundedQaMessages(
        question: String,
        sources: List<GroundedSourceSnippet>
    ): List<UoaiChatMessage> {
        val context = buildBoundedSourceContext(sources, computeSourceCharBudget())
        val systemPrompt = """
            You are a 100% offline Personal AI Memory Assistant. Answer the user's question ONLY using the numbered transcript and memory sources below.
            Rules:
            1. NEVER fabricate memories, names, dates, or facts not present in the sources.
            2. Cite source tags like [S1], [S2] inline with timestamps when referencing facts.
            3. If a source is marked [UNCERTAIN], explicitly state that it is uncertain rather than a verified fact.
            4. If the sources do not answer the question, state clearly that no matching memory was found.
            5. Respond clearly in the user's language (English, Hindi, or Hinglish).
        """.trimIndent()
        return listOf(
            UoaiChatMessage("system", systemPrompt),
            UoaiChatMessage("user", "Question: $question\n\nSources:\n$context")
        )
    }

    private fun computeSourceCharBudget(): Int {
        val ctxTokens = (uoaiClient.cachedCapabilities?.contextWindowTokens ?: 8_192).coerceAtLeast(2_048)
        // Reserve room for system prompt + output tokens; ~3 chars/token conservatively.
        return ((ctxTokens - 2_048).coerceAtLeast(1_024) * 3).coerceAtMost(90_000)
    }

    private fun buildBoundedSourceContext(
        sources: List<GroundedSourceSnippet>,
        maxChars: Int
    ): String {
        val sb = StringBuilder()
        for (s in sources) {
            val certNote = if (s.certaintyLevel == CertaintyLevel.UNCERTAIN) " [UNCERTAIN]" else ""
            val catNote = s.category?.let { "(${it.name}) " } ?: ""
            val line = "[${s.citationTag} | ${s.formattedTime}]$certNote $catNote${s.text}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString().trim()
    }
}
