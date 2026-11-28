package com.personalai.primary.memory

import com.personalai.primary.data.db.AskAiAuditEntity
import com.personalai.primary.data.db.CertaintyLevel
import com.personalai.primary.data.db.EmbeddingOwnerType
import com.personalai.primary.data.db.EmbeddingVectorEntity
import com.personalai.primary.data.db.EntityNodeEntity
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryDao
import com.personalai.primary.data.db.MemoryEntityCrossRef
import com.personalai.primary.data.db.MemoryExtractionStatus
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.SummaryEntity
import com.personalai.primary.data.db.SummaryType
import com.personalai.primary.data.db.TaskReminderEntity
import com.personalai.primary.data.db.TaskReminderStatus
import com.personalai.primary.data.db.TranscriptDao
import com.personalai.primary.data.db.TranscriptEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MemoryEngineTest {

    private val offlineProvider = LocalHeuristicLlmProvider()

    @Test
    fun `multilingual embedder preserves vectors and ranks related EN and Hindi text higher`() {
        val v1 = LocalSemanticEmbedder.embed("Send the client proposal to Rahul tomorrow")
        val vRelated = LocalSemanticEmbedder.embed("proposal for client Rahul by tomorrow")
        val vUnrelated = LocalSemanticEmbedder.embed("chocolate cake recipe oven baking")

        assertEquals(LocalSemanticEmbedder.DIMENSIONS, v1.size)
        val simRelated = LocalSemanticEmbedder.cosineSimilarity(v1, vRelated)
        val simUnrelated = LocalSemanticEmbedder.cosineSimilarity(v1, vUnrelated)
        assertTrue("Expected related similarity ($simRelated) > unrelated ($simUnrelated)", simRelated > simUnrelated + 0.25f)

        val hi1 = LocalSemanticEmbedder.embed("कल प्रोजेक्ट मीटिंग का फैसला करना है")
        val hiRelated = LocalSemanticEmbedder.embed("प्रोजेक्ट मीटिंग कल तय फैसला")
        val hiUnrelated = LocalSemanticEmbedder.embed("बारिश और चाय के साथ समोसा")
        val hiSimRelated = LocalSemanticEmbedder.cosineSimilarity(hi1, hiRelated)
        val hiSimUnrelated = LocalSemanticEmbedder.cosineSimilarity(hi1, hiUnrelated)
        assertTrue("Expected Hindi related ($hiSimRelated) > unrelated ($hiSimUnrelated)", hiSimRelated > hiSimUnrelated + 0.2f)

        val encoded = LocalSemanticEmbedder.toByteArray(v1)
        val decoded = LocalSemanticEmbedder.fromByteArray(encoded)
        assertEquals(v1.size, decoded.size)
        for (i in v1.indices) {
            assertEquals(v1[i], decoded[i], 1e-6f)
        }
    }

    @Test
    fun `offline extractor detects commitments deadlines decisions ideas entities and Hindi markers honestly`() = runTest {
        val baseTs = 1_760_000_000_000L

        val enDrafts = offlineProvider.extractMemories(
            transcriptText = "I promise I will send the roadmap to Rahul Verma by tomorrow. We decided to ship Android 16 support first. What if we add a business idea tracker?",
            language = "en",
            timestampMs = baseTs
        ).getOrThrow()

        assertTrue("Should extract deadline or commitment", enDrafts.any {
            it.category == MemoryCategory.DEADLINE || it.category == MemoryCategory.COMMITMENT
        })
        assertTrue("Should extract decision", enDrafts.any { it.category == MemoryCategory.DECISION })
        assertTrue("Should extract idea", enDrafts.any { it.category == MemoryCategory.IDEA })
        assertTrue("Should infer tomorrow due date", enDrafts.any { it.dueLabel == "Tomorrow 10:00" && it.dueAtMs != null })
        assertTrue("Should extract Rahul Verma entity", enDrafts.any { d -> d.entities.any { it.name.contains("Rahul") } })
        assertTrue("Heuristic extractor must not overclaim EXPLICIT certainty", enDrafts.none { it.certaintyLevel == CertaintyLevel.EXPLICIT })

        val hiDrafts = offlineProvider.extractMemories(
            transcriptText = "मुझे कल क्लाइंट को रिपोर्ट भेजना है।",
            language = "hi",
            timestampMs = baseTs
        ).getOrThrow()
        assertTrue("Should extract Hindi task/deadline", hiDrafts.isNotEmpty())
        assertTrue("Should infer Hindi tomorrow due label", hiDrafts.any { it.dueLabel == "Tomorrow 10:00" })

        val inaudible = offlineProvider.extractMemories("[Inaudible audio]", "en", baseTs).getOrThrow()
        assertTrue("Inaudible placeholder should yield no memories", inaudible.isEmpty())
    }

    @Test
    fun `offline summary groups decisions tasks and follow-ups from sources`() = runTest {
        val sources = listOf(
            GroundedSourceSnippet("S1", "tr-1", null, 1_000L, "Nov 19 09:00", null, null, "We agreed on the Q4 budget."),
            GroundedSourceSnippet("M2", "tr-1", "mem-1", 1_000L, "Nov 19 09:00", MemoryCategory.DECISION, CertaintyLevel.INFERRED_MEDIUM, "Approved Q4 budget"),
            GroundedSourceSnippet("M3", "tr-2", "mem-2", 2_000L, "Nov 19 10:00", MemoryCategory.TASK, CertaintyLevel.INFERRED_MEDIUM, "Email finance sheet by Friday"),
            GroundedSourceSnippet("M4", "tr-3", "mem-3", 3_000L, "Nov 19 11:00", MemoryCategory.FOLLOW_UP, CertaintyLevel.UNCERTAIN, "Follow up with vendor")
        )

        val summary = offlineProvider.generateSummary(SummaryType.DAILY, "Nov 19, 2026", sources).getOrThrow()
        assertTrue(summary.markdownBody.contains("[S1 • Nov 19 09:00]"))
        assertEquals(listOf("Approved Q4 budget"), summary.keyDecisions)
        assertEquals(listOf("Email finance sheet by Friday"), summary.pendingTasks)
        assertEquals(listOf("Follow up with vendor"), summary.followUps)

        val emptyRes = offlineProvider.generateSummary(SummaryType.DAILY, "Nov 19, 2026", emptyList())
        assertTrue(emptyRes.isFailure)
    }

    @Test
    fun `grounded question answering refuses when empty and labels uncertain extractions`() = runTest {
        val emptyAnswer = offlineProvider.answerGroundedQuestion("What did I buy on Mars?", emptyList()).getOrThrow()
        assertTrue(emptyAnswer.contains("couldn't find", ignoreCase = true))
        assertTrue(emptyAnswer.contains("No facts were invented", ignoreCase = true))

        val grounded = offlineProvider.answerGroundedQuestion(
            question = "What did we discuss about the app?",
            sources = listOf(
                GroundedSourceSnippet(
                    citationTag = "S1",
                    transcriptId = "tr-1",
                    memoryId = "mem-1",
                    timestampMs = 1_000L,
                    formattedTime = "Nov 19 12:00",
                    category = MemoryCategory.IDEA,
                    certaintyLevel = CertaintyLevel.UNCERTAIN,
                    text = "Build an offline-first memory search index."
                )
            )
        ).getOrThrow()
        assertTrue(grounded.contains("[S1 | Nov 19 12:00]"))
        assertTrue(grounded.contains("uncertain extraction", ignoreCase = true))
    }

    @Test
    fun `search engine parses English and Hindi temporal windows and enforces zero-fabrication retrieval`() = runTest {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(2026, Calendar.NOVEMBER, 19, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMs = cal.timeInMillis
        val yesterdayMidday = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        val tenDaysAgo = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -10) }.timeInMillis

        val trYesterday = TranscriptEntity(
            transcriptId = "tr-yest",
            segmentId = "seg-1",
            sessionId = "sess-1",
            text = "Yesterday I talked with Anita about launching the solar battery product.",
            language = "en",
            startTimeMs = yesterdayMidday,
            endTimeMs = yesterdayMidday + 5_000L,
            confidence = 0.9f,
            asrProvider = "test",
            createdAtMs = yesterdayMidday
        )
        val trOld = TranscriptEntity(
            transcriptId = "tr-old",
            segmentId = "seg-2",
            sessionId = "sess-0",
            text = "Old note about grocery vegetables and milk.",
            language = "en",
            startTimeMs = tenDaysAgo,
            endTimeMs = tenDaysAgo + 5_000L,
            confidence = 0.9f,
            asrProvider = "test",
            createdAtMs = tenDaysAgo
        )
        val memCommitment = MemoryItemEntity(
            memoryId = "mem-commit",
            category = MemoryCategory.COMMITMENT,
            title = "Send deck to Priya",
            content = "I will send the pitch deck to Priya by Friday.",
            sourceTranscriptId = "tr-yest",
            sourceSegmentId = "seg-1",
            sessionId = "sess-1",
            sourceTimestampMs = yesterdayMidday,
            confidence = 0.8f,
            certaintyLevel = CertaintyLevel.INFERRED_MEDIUM,
            entitiesCsv = "Priya",
            extractedAtMs = yesterdayMidday
        )

        val fakeTranscriptDao = FakeTranscriptDao(listOf(trYesterday, trOld))
        val fakeMemoryDao = FakeMemoryDao(listOf(memCommitment))
        val engine = MemorySearchEngine(fakeTranscriptDao, fakeMemoryDao)
        engine.indexTranscript(trYesterday)
        engine.indexTranscript(trOld)
        engine.indexMemory(memCommitment)

        // Temporal parsing (English + Hindi)
        val yWin = engine.parseTemporalWindow("What did I discuss yesterday?", nowMs)
        assertNotNull(yWin)
        assertEquals("Yesterday", yWin!!.label)
        assertTrue(yesterdayMidday in yWin.startMs..yWin.endMs)
        assertFalse(tenDaysAgo in yWin.startMs..yWin.endMs)

        assertEquals("Today", engine.parseTemporalWindow("आज क्या बात हुई?", nowMs)?.label)
        assertEquals("Yesterday", engine.parseTemporalWindow("बीते कल की मीटिंग", nowMs)?.label)
        assertEquals("Last Week", engine.parseTemporalWindow("What happened last week?", nowMs)?.label)
        assertNull(engine.parseTemporalWindow("Tell me about Priya", nowMs))

        // Temporal recall scopes strictly to yesterday
        val yesterdayHits = engine.retrieveGroundedContextForQuestion("What did I discuss yesterday?", nowMs = nowMs)
        assertTrue(yesterdayHits.any { it.transcriptId == "tr-yest" })
        assertTrue(yesterdayHits.none { it.transcriptId == "tr-old" })

        // Intent-based commitment retrieval
        val promiseHits = engine.retrieveGroundedContextForQuestion("What promises did I make?", nowMs = nowMs)
        assertTrue(promiseHits.any { it.memoryId == "mem-commit" })

        // Completely unrelated query must return empty sources (zero fabrication)
        val unrelatedHits = engine.retrieveGroundedContextForQuestion("Quantum astrophysics telescope orbital mechanics", nowMs = nowMs)
        assertTrue("Expected zero hits for unrelated query, got: $unrelatedHits", unrelatedHits.isEmpty())
    }

    private class FakeTranscriptDao(initial: List<TranscriptEntity>) : TranscriptDao {
        private val items = initial.toMutableList()
        override suspend fun upsert(transcript: TranscriptEntity) {
            items.removeAll { it.transcriptId == transcript.transcriptId }
            items.add(transcript)
        }
        override suspend fun getById(id: String) = items.find { it.transcriptId == id }
        override suspend fun getBySegmentId(segmentId: String) = items.find { it.segmentId == segmentId }
        override suspend fun updateInPlace(
            transcriptId: String,
            text: String,
            language: String,
            confidence: Float,
            asrProvider: String,
            createdAtMs: Long
        ) {
            val idx = items.indexOfFirst { it.transcriptId == transcriptId }
            if (idx >= 0) {
                items[idx] = items[idx].copy(
                    text = text,
                    language = language,
                    confidence = confidence,
                    asrProvider = asrProvider,
                    createdAtMs = createdAtMs
                )
            }
        }
        override suspend fun getByIds(ids: List<String>) = items.filter { it.transcriptId in ids }
        override fun observeRecentTranscripts(limit: Int): Flow<List<TranscriptEntity>> = flowOf(items.take(limit))
        override suspend fun getRecentTranscripts(limit: Int) = items.sortedByDescending { it.startTimeMs }.take(limit)
        override suspend fun getByDateRange(startMs: Long, endMs: Long) =
            items.filter { it.startTimeMs in startMs..endMs }.sortedBy { it.startTimeMs }
        override suspend fun getBySession(sessionId: String) = items.filter { it.sessionId == sessionId }
        override suspend fun getPendingMemoryExtraction() = items.filter { it.memoryExtractionStatus == MemoryExtractionStatus.PENDING }
        override suspend fun updateMemoryExtractionStatus(transcriptId: String, status: MemoryExtractionStatus) {}
        override suspend fun resetFailedExtractionToPending() {}
        override suspend fun searchFts(ftsQuery: String): List<TranscriptEntity> {
            val tokens = ftsQuery.replace("*", "").split(" OR ").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            return items.filter { tr -> tokens.any { tr.text.lowercase().contains(it) } }
        }
        override suspend fun searchSubstring(query: String) =
            items.filter { it.text.contains(query, ignoreCase = true) }
        override suspend fun deleteAll() { items.clear() }
    }

    private class FakeMemoryDao(initial: List<MemoryItemEntity>) : MemoryDao {
        private val memories = initial.toMutableList()
        private val embeddings = mutableListOf<EmbeddingVectorEntity>()
        override suspend fun insertMemories(items: List<MemoryItemEntity>) { memories.addAll(items) }
        override fun observeActiveMemories(limit: Int): Flow<List<MemoryItemEntity>> = flowOf(memories.filterNot { it.isArchived })
        override fun observeArchivedMemories(limit: Int): Flow<List<MemoryItemEntity>> = flowOf(memories.filter { it.isArchived })
        override suspend fun getActiveMemories(limit: Int) = memories.filterNot { it.isArchived }.take(limit)
        override suspend fun getById(memoryId: String) = memories.find { it.memoryId == memoryId }
        override suspend fun getByIds(ids: List<String>) = memories.filter { it.memoryId in ids }
        override suspend fun getByDateRange(startMs: Long, endMs: Long) =
            memories.filter { !it.isArchived && it.sourceTimestampMs in startMs..endMs }
        override suspend fun deleteBySourceTranscript(transcriptId: String) { memories.removeAll { it.sourceTranscriptId == transcriptId } }
        override suspend fun archiveMemory(memoryId: String, updatedAtMs: Long) {}
        override suspend fun restoreMemory(memoryId: String, updatedAtMs: Long) {}
        override suspend fun setMemoryPinned(memoryId: String, pinned: Boolean, updatedAtMs: Long) {}
        override suspend fun updateMemoryContent(memoryId: String, title: String, content: String, category: MemoryCategory, updatedAtMs: Long) {}
        override suspend fun deleteMemoryById(memoryId: String) { memories.removeAll { it.memoryId == memoryId } }
        override suspend fun deleteEntitiesByNormalizedNames(names: List<String>) {}
        override suspend fun deleteAllMemories() { memories.clear() }
        override suspend fun upsertEntityNode(entity: EntityNodeEntity) {}
        override suspend fun findEntityByNormalizedName(normalized: String): EntityNodeEntity? = null
        override fun observeTopEntities(limit: Int): Flow<List<EntityNodeEntity>> = flowOf(emptyList())
        override suspend fun insertMemoryEntityRefs(refs: List<MemoryEntityCrossRef>) {}
        override suspend fun deleteAllEntities() {}
        override suspend fun insertTasks(tasks: List<TaskReminderEntity>) {}
        override fun observeTasks(): Flow<List<TaskReminderEntity>> = flowOf(emptyList())
        override suspend fun getOpenTasks(): List<TaskReminderEntity> = emptyList()
        override suspend fun updateTaskStatus(taskId: String, status: TaskReminderStatus) {}
        override suspend fun updateTaskPriority(taskId: String, priority: Int) {}
        override suspend fun markTaskNotificationScheduled(taskId: String) {}
        override suspend fun deleteAllTasks() {}
        override suspend fun upsertSummary(summary: SummaryEntity) {}
        override fun observeSummaries(limit: Int): Flow<List<SummaryEntity>> = flowOf(emptyList())
        override suspend fun deleteAllSummaries() {}
        override suspend fun upsertEmbedding(vector: EmbeddingVectorEntity) {
            embeddings.removeAll { it.ownerType == vector.ownerType && it.ownerId == vector.ownerId && it.modelId == vector.modelId }
            embeddings.add(vector)
        }
        override suspend fun getEmbeddingsByModel(modelId: String) = embeddings.filter { it.modelId == modelId }
        override suspend fun deleteAllEmbeddings() { embeddings.clear() }
        override suspend fun insertAskAiAudit(audit: AskAiAuditEntity) {}
        override fun observeAskAiHistory(limit: Int): Flow<List<AskAiAuditEntity>> = flowOf(emptyList())
        override suspend fun deleteAllAskAiHistory() {}
    }
}
