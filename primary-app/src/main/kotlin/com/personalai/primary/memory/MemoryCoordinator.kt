package com.personalai.primary.memory

import com.personalai.primary.data.db.AsrProcessingStatus
import com.personalai.primary.data.db.AskAiAuditEntity
import com.personalai.primary.data.db.CertaintyLevel
import com.personalai.primary.data.db.EntityNodeEntity
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryDao
import com.personalai.primary.data.db.MemoryEntityCrossRef
import com.personalai.primary.data.db.MemoryExtractionStatus
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.SummaryEntity
import com.personalai.primary.data.db.SummaryType
import com.personalai.primary.data.db.SyncedContentDao
import com.personalai.primary.data.db.SyncedSegmentEntity
import com.personalai.primary.data.db.TaskReminderEntity
import com.personalai.primary.data.db.TaskReminderStatus
import com.personalai.primary.data.db.TranscriptDao
import com.personalai.primary.data.db.TranscriptEntity
import com.personalai.primary.data.prefs.PrimarySettingsRepository
import com.personalai.primary.memory.uoai.UoaiClient
import com.personalai.primary.memory.uoai.UoaiException
import com.personalai.primary.memory.uoai.UoaiLlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class MemoryEngineStatusSnapshot(
    val isExtracting: Boolean = false,
    val isSummarizing: Boolean = false,
    val isAnswering: Boolean = false,
    val lastProviderUsed: String? = null,
    val lastError: String? = null
)

@Singleton
class MemoryCoordinator @Inject constructor(
    private val syncedContentDao: SyncedContentDao,
    private val transcriptDao: TranscriptDao,
    private val memoryDao: MemoryDao,
    private val searchEngine: MemorySearchEngine,
    private val settingsRepo: PrimarySettingsRepository,
    private val uoaiClient: UoaiClient,
    private val uoaiLlm: UoaiLlmProvider,
    private val offlineLlm: LocalHeuristicLlmProvider,
    private val reminderScheduler: ReminderScheduler
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractMutex = Mutex()

    private val _status = MutableStateFlow(MemoryEngineStatusSnapshot())
    val status: StateFlow<MemoryEngineStatusSnapshot> = _status.asStateFlow()

    init {
        scope.launch {
            runCatching {
                memoryDao.deleteEntitiesByNormalizedNames(EntityHygiene.JUNK_NORMALIZED_NAMES.toList())
            }
        }
    }

    fun enqueuePendingExtraction() {
        scope.launch {
            extractPendingTranscripts()
        }
    }

    fun retryFailedExtractions() {
        scope.launch {
            transcriptDao.resetFailedExtractionToPending()
            extractPendingTranscripts()
        }
    }

    suspend fun extractPendingTranscripts() = extractMutex.withLock {
        val pending = transcriptDao.getPendingMemoryExtraction()
        if (pending.isEmpty()) return@withLock

        _status.value = _status.value.copy(isExtracting = true, lastError = null)
        val provider = resolveActiveLlmProvider()

        for (transcript in pending) {
            extractForSingleTranscript(transcript, provider)
        }
        _status.value = _status.value.copy(isExtracting = false)
    }

    private suspend fun extractForSingleTranscript(
        transcript: TranscriptEntity,
        provider: LlmProvider
    ) {
        // Always index transcript vector first so Ask AI & search can ground on raw transcript even if extraction fails.
        runCatching { searchEngine.indexTranscript(transcript) }

        var usedProviderName = provider.providerName
        val res = provider.extractMemories(
            transcriptText = transcript.text,
            language = transcript.language,
            timestampMs = transcript.startTimeMs
        ).recoverCatching { err ->
            // Fall back cleanly to offline extractor if cloud/local server fails so transcripts still become searchable memories.
            if (provider !== offlineLlm) {
                usedProviderName = "${offlineLlm.providerName} (fallback)"
                _status.value = _status.value.copy(lastError = "LLM fallback to offline extractor: ${err.message}")
                offlineLlm.extractMemories(transcript.text, transcript.language, transcript.startTimeMs).getOrThrow()
            } else {
                throw err
            }
        }

        res.onSuccess { drafts ->
            val now = System.currentTimeMillis()
            memoryDao.deleteBySourceTranscript(transcript.transcriptId)

            val memoryEntities = mutableListOf<MemoryItemEntity>()
            val taskEntities = mutableListOf<TaskReminderEntity>()
            val crossRefs = mutableListOf<MemoryEntityCrossRef>()

            for (draft in drafts) {
                val memId = UUID.randomUUID().toString()
                val entityNames = draft.entities.map { it.name }.distinct()
                val mem = MemoryItemEntity(
                    memoryId = memId,
                    category = draft.category,
                    title = draft.title,
                    content = draft.content,
                    sourceTranscriptId = transcript.transcriptId,
                    sourceSegmentId = transcript.segmentId,
                    sessionId = transcript.sessionId,
                    sourceTimestampMs = transcript.startTimeMs,
                    confidence = draft.confidence,
                    certaintyLevel = draft.certaintyLevel,
                    entitiesCsv = entityNames.joinToString(", "),
                    extractedAtMs = now
                )
                memoryEntities.add(mem)

                for (ent in draft.entities) {
                    if (!EntityHygiene.isValidEntityName(ent.name)) continue
                    val normalized = ent.name.trim().lowercase()
                    val existing = memoryDao.findEntityByNormalizedName(normalized)
                    val nodeId = existing?.entityId ?: UUID.randomUUID().toString()
                    memoryDao.upsertEntityNode(
                        EntityNodeEntity(
                            entityId = nodeId,
                            name = existing?.name ?: ent.name.trim(),
                            normalizedName = normalized,
                            entityType = existing?.entityType ?: ent.type,
                            mentionCount = (existing?.mentionCount ?: 0) + 1,
                            lastMentionedMs = maxOf(existing?.lastMentionedMs ?: 0L, transcript.startTimeMs)
                        )
                    )
                    crossRefs.add(MemoryEntityCrossRef(memoryId = memId, entityId = nodeId))
                }

                if (draft.category == MemoryCategory.TASK ||
                    draft.category == MemoryCategory.COMMITMENT ||
                    draft.category == MemoryCategory.DEADLINE ||
                    draft.category == MemoryCategory.FOLLOW_UP
                ) {
                    val task = TaskReminderEntity(
                        taskId = UUID.randomUUID().toString(),
                        memoryId = memId,
                        sourceTranscriptId = transcript.transcriptId,
                        title = draft.title,
                        notes = draft.content,
                        dueAtMs = draft.dueAtMs,
                        dueLabel = draft.dueLabel,
                        status = TaskReminderStatus.OPEN,
                        notificationScheduled = false,
                        createdAtMs = now
                    )
                    taskEntities.add(task)
                }
            }

            if (memoryEntities.isNotEmpty()) {
                memoryDao.insertMemories(memoryEntities)
                for (m in memoryEntities) {
                    runCatching { searchEngine.indexMemory(m) }
                }
            }
            if (crossRefs.isNotEmpty()) {
                memoryDao.insertMemoryEntityRefs(crossRefs)
            }
            if (taskEntities.isNotEmpty()) {
                memoryDao.insertTasks(taskEntities)
                for (task in taskEntities) {
                    if (reminderScheduler.scheduleTaskReminder(task)) {
                        memoryDao.markTaskNotificationScheduled(task.taskId)
                    }
                }
            }

            transcriptDao.updateMemoryExtractionStatus(transcript.transcriptId, MemoryExtractionStatus.COMPLETED)
            _status.value = _status.value.copy(lastProviderUsed = usedProviderName)
        }.onFailure { err ->
            // PRD Section 17: AI extraction failure must preserve the original transcript.
            transcriptDao.updateMemoryExtractionStatus(transcript.transcriptId, MemoryExtractionStatus.FAILED)
            _status.value = _status.value.copy(lastError = "Memory extraction failed: ${err.message}")
        }
    }

    suspend fun generateSummary(
        summaryType: SummaryType,
        sessionId: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Result<SummaryEntity> {
        _status.value = _status.value.copy(isSummarizing = true, lastError = null)
        return try {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val dayFmt = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

            val (startMs, endMs, periodLabel, transcripts, memories) = when (summaryType) {
                SummaryType.DAILY -> {
                    val s = (cal.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    val e = nowMs
                    SummaryScope(
                        s, e, dayFmt.format(Date(nowMs)),
                        transcriptDao.getByDateRange(s, e),
                        memoryDao.getByDateRange(s, e)
                    )
                }
                SummaryType.WEEKLY -> {
                    val s = (cal.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, -6)
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    val e = nowMs
                    SummaryScope(
                        s, e, "${dayFmt.format(Date(s))} – ${dayFmt.format(Date(e))}",
                        transcriptDao.getByDateRange(s, e),
                        memoryDao.getByDateRange(s, e)
                    )
                }
                SummaryType.CONVERSATION, SummaryType.MEETING -> {
                    val trs = if (sessionId != null) {
                        transcriptDao.getBySession(sessionId)
                    } else {
                        transcriptDao.getRecentTranscripts(40).reversed()
                    }
                    val s = trs.minOfOrNull { it.startTimeMs } ?: nowMs
                    val e = trs.maxOfOrNull { it.endTimeMs } ?: nowMs
                    val mems = memoryDao.getByDateRange(s, e)
                    SummaryScope(s, e, sessionId?.take(8) ?: "Latest Conversations", trs, mems)
                }
            }

            val spokenTranscripts = transcripts.filterNot { it.text.startsWith("[Inaudible") || it.text.isBlank() }
            if (spokenTranscripts.isEmpty()) {
                val err = IllegalStateException("No spoken transcripts recorded for $periodLabel yet.")
                _status.value = _status.value.copy(isSummarizing = false, lastError = err.message)
                return Result.failure(err)
            }

            val sources = buildList {
                var idx = 1
                for (tr in spokenTranscripts.take(25)) {
                    add(
                        GroundedSourceSnippet(
                            citationTag = "S${idx++}",
                            transcriptId = tr.transcriptId,
                            timestampMs = tr.startTimeMs,
                            formattedTime = searchEngine.formatTimestamp(tr.startTimeMs),
                            text = tr.text
                        )
                    )
                }
                for (m in memories.take(25)) {
                    add(
                        GroundedSourceSnippet(
                            citationTag = "M${idx++}",
                            transcriptId = m.sourceTranscriptId,
                            memoryId = m.memoryId,
                            timestampMs = m.sourceTimestampMs,
                            formattedTime = searchEngine.formatTimestamp(m.sourceTimestampMs),
                            category = m.category,
                            certaintyLevel = m.certaintyLevel,
                            text = m.content
                        )
                    )
                }
            }

            val provider = resolveActiveLlmProvider()
            var usedProviderName = provider.providerName
            val draftRes = provider.generateSummary(summaryType, periodLabel, sources)
                .recoverCatching { err ->
                    if (provider !== offlineLlm) {
                        usedProviderName = "${offlineLlm.providerName} (fallback)"
                        _status.value = _status.value.copy(lastError = "Summary fallback to offline engine: ${err.message}")
                        offlineLlm.generateSummary(summaryType, periodLabel, sources).getOrThrow()
                    } else {
                        throw err
                    }
                }

            val draft = draftRes.getOrThrow()
            val entity = SummaryEntity(
                summaryId = UUID.randomUUID().toString(),
                summaryType = summaryType,
                title = draft.title,
                periodStartMs = startMs,
                periodEndMs = endMs,
                sessionId = sessionId,
                markdownBody = draft.markdownBody,
                keyDecisionsJson = json.encodeToString(draft.keyDecisions),
                pendingTasksJson = json.encodeToString(draft.pendingTasks),
                followUpsJson = json.encodeToString(draft.followUps),
                sourceTranscriptIdsJson = json.encodeToString(transcripts.map { it.transcriptId }),
                createdAtMs = System.currentTimeMillis()
            )
            memoryDao.upsertSummary(entity)
            _status.value = _status.value.copy(isSummarizing = false, lastProviderUsed = usedProviderName)
            Result.success(entity)
        } catch (t: Throwable) {
            _status.value = _status.value.copy(isSummarizing = false, lastError = t.message)
            Result.failure(t)
        }
    }

    suspend fun askQuestion(
        question: String,
        nowMs: Long = System.currentTimeMillis(),
        onStreamToken: (String) -> Unit = {}
    ): AskAiAnswer {
        val cleaned = question.trim()
        _status.value = _status.value.copy(isAnswering = true, lastError = null)
        return try {
            val sources = searchEngine.retrieveGroundedContextForQuestion(cleaned, nowMs = nowMs, maxSources = 8)
            val queryId = UUID.randomUUID().toString()

            if (sources.isEmpty()) {
                val refusal = "I couldn't find any recorded conversations or memories matching \"$cleaned\". I will not invent memories."
                memoryDao.insertAskAiAudit(
                    AskAiAuditEntity(
                        queryId = queryId,
                        question = cleaned,
                        answer = refusal,
                        citedTranscriptIdsJson = "[]",
                        citedMemoryIdsJson = "[]",
                        providerUsed = "Grounding Guard",
                        createdAtMs = nowMs
                    )
                )
                _status.value = _status.value.copy(isAnswering = false)
                return AskAiAnswer(
                    queryId = queryId,
                    question = cleaned,
                    answer = refusal,
                    citedSources = emptyList(),
                    providerUsed = "Grounding Guard",
                    grounded = true
                )
            }

            val provider = resolveActiveLlmProvider()
            var usedProviderName = provider.providerName
            val answerText = if (provider === uoaiLlm && uoaiLlm.supportsStreaming()) {
                val streamed = StringBuilder()
                try {
                    uoaiLlm.streamGroundedQuestion(cleaned, sources).collect { token ->
                        streamed.append(token)
                        onStreamToken(streamed.toString())
                    }
                    streamed.toString().trim().ifBlank {
                        offlineLlm.answerGroundedQuestion(cleaned, sources).getOrThrow()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Throwable) {
                    if (err is UoaiException.NotRegistered ||
                        err is UoaiException.ProviderNotRunning ||
                        err is UoaiException.ModelNotReady
                    ) {
                        uoaiClient.checkConnection()
                    }
                    usedProviderName = "${offlineLlm.providerName} (fallback)"
                    _status.value = _status.value.copy(lastError = "UOAI fallback: ${err.message}")
                    offlineLlm.answerGroundedQuestion(cleaned, sources).getOrElse {
                        "Unable to generate answer right now: ${it.message}"
                    }
                }
            } else {
                provider.answerGroundedQuestion(cleaned, sources)
                    .recoverCatching { err ->
                        if (provider === uoaiLlm) {
                            uoaiClient.checkConnection()
                        }
                        if (provider !== offlineLlm) {
                            usedProviderName = "${offlineLlm.providerName} (fallback)"
                            _status.value = _status.value.copy(lastError = "LLM fallback: ${err.message}")
                            offlineLlm.answerGroundedQuestion(cleaned, sources).getOrThrow()
                        } else {
                            throw err
                        }
                    }
                    .getOrElse { "Unable to generate answer right now: ${it.message}" }
            }

            val citedTranscriptIds = sources.map { it.transcriptId }.distinct()
            val citedMemoryIds = sources.mapNotNull { it.memoryId }.distinct()
            memoryDao.insertAskAiAudit(
                AskAiAuditEntity(
                    queryId = queryId,
                    question = cleaned,
                    answer = answerText,
                    citedTranscriptIdsJson = json.encodeToString(citedTranscriptIds),
                    citedMemoryIdsJson = json.encodeToString(citedMemoryIds),
                    providerUsed = usedProviderName,
                    createdAtMs = System.currentTimeMillis()
                )
            )
            _status.value = _status.value.copy(isAnswering = false, lastProviderUsed = usedProviderName)
            AskAiAnswer(
                queryId = queryId,
                question = cleaned,
                answer = answerText,
                citedSources = sources,
                providerUsed = usedProviderName,
                grounded = true
            )
        } finally {
            _status.value = _status.value.copy(isAnswering = false)
        }
    }

    suspend fun updateTaskStatus(taskId: String, status: TaskReminderStatus) {
        memoryDao.updateTaskStatus(taskId, status)
        if (status != TaskReminderStatus.OPEN) {
            reminderScheduler.cancelTaskReminder(taskId)
        }
    }

    suspend fun updateTaskPriority(taskId: String, priority: Int) {
        memoryDao.updateTaskPriority(taskId, priority.coerceIn(0, 2))
    }

    suspend fun createManualTask(
        title: String,
        notes: String = "",
        dueLabel: String? = null,
        priority: Int = 1
    ): Result<TaskReminderEntity> = runCatching {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "Task title cannot be empty." }
        val now = System.currentTimeMillis()
        val task = TaskReminderEntity(
            taskId = UUID.randomUUID().toString(),
            memoryId = null,
            sourceTranscriptId = "manual_task_$now",
            title = cleanTitle,
            notes = notes.trim(),
            dueAtMs = null,
            dueLabel = dueLabel?.trim()?.takeIf { it.isNotEmpty() },
            status = TaskReminderStatus.OPEN,
            notificationScheduled = false,
            createdAtMs = now,
            priority = priority.coerceIn(0, 2)
        )
        memoryDao.insertTasks(listOf(task))
        task
    }

    suspend fun editTask(
        existing: TaskReminderEntity,
        title: String,
        notes: String,
        dueLabel: String?,
        priority: Int
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return
        val updated = existing.copy(
            title = cleanTitle,
            notes = notes.trim(),
            dueLabel = dueLabel?.trim()?.takeIf { it.isNotEmpty() },
            priority = priority.coerceIn(0, 2)
        )
        memoryDao.insertTasks(listOf(updated))
    }

    suspend fun archiveMemory(memoryId: String) {
        memoryDao.archiveMemory(memoryId, System.currentTimeMillis())
    }

    suspend fun restoreArchivedMemory(memoryId: String) {
        memoryDao.restoreMemory(memoryId, System.currentTimeMillis())
    }

    suspend fun togglePinMemory(memoryId: String, pinned: Boolean) {
        memoryDao.setMemoryPinned(memoryId, pinned, System.currentTimeMillis())
    }

    suspend fun editMemory(
        memoryId: String,
        title: String,
        content: String,
        category: MemoryCategory
    ) {
        val cleanTitle = title.trim().ifBlank { content.trim().take(60) }
        val cleanContent = content.trim()
        if (cleanContent.isBlank()) return
        val now = System.currentTimeMillis()
        memoryDao.updateMemoryContent(memoryId, cleanTitle, cleanContent, category, now)
        memoryDao.getById(memoryId)?.let { updated ->
            runCatching { searchEngine.indexMemory(updated) }
        }
    }

    suspend fun deleteMemoryPermanently(memoryId: String) {
        memoryDao.deleteMemoryById(memoryId)
        searchEngine.evictMemoryVector(memoryId)
    }

    suspend fun promoteMemoryToTask(memory: MemoryItemEntity, priority: Int = 2) {
        val now = System.currentTimeMillis()
        val task = TaskReminderEntity(
            taskId = UUID.randomUUID().toString(),
            memoryId = memory.memoryId,
            sourceTranscriptId = memory.sourceTranscriptId,
            title = memory.title.ifBlank { memory.content.take(64) },
            notes = memory.content,
            dueAtMs = null,
            dueLabel = null,
            status = TaskReminderStatus.OPEN,
            notificationScheduled = false,
            createdAtMs = now,
            priority = priority.coerceIn(0, 2)
        )
        memoryDao.insertTasks(listOf(task))
    }

    suspend fun captureManualMemory(
        title: String,
        content: String,
        category: MemoryCategory,
        pin: Boolean,
        createTask: Boolean
    ): Result<MemoryItemEntity> = runCatching {
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "Note content cannot be empty." }
        val cleanTitle = title.trim().ifBlank { cleanContent.take(64) }
        val now = System.currentTimeMillis()
        val segId = "note-${UUID.randomUUID()}"
        val trId = "tr-${UUID.randomUUID()}"
        val memId = UUID.randomUUID().toString()
        val minSeq = syncedContentDao.getMinSequenceNumber()
        val syntheticSeq = minOf(0L, minSeq) - 1L

        syncedContentDao.insertSegment(
            SyncedSegmentEntity(
                segmentId = segId,
                sessionId = "manual_notes",
                sequenceNumber = syntheticSeq,
                startTimestampMs = now,
                endTimestampMs = now,
                durationMs = 0L,
                localEncryptedAudioPath = "",
                wavSizeBytes = 0L,
                checksum = "manual",
                syncedAtMs = now,
                asrStatus = AsrProcessingStatus.COMPLETED,
                audioDeleted = true,
                audioDeletedAtMs = now
            )
        )

        val transcript = TranscriptEntity(
            transcriptId = trId,
            segmentId = segId,
            sessionId = "manual_notes",
            text = "$cleanTitle — $cleanContent",
            language = "en",
            startTimeMs = now,
            endTimeMs = now,
            confidence = 1.0f,
            asrProvider = "Manual 2nd Mind Note",
            createdAtMs = now,
            memoryExtractionStatus = MemoryExtractionStatus.COMPLETED
        )
        transcriptDao.upsert(transcript)
        runCatching { searchEngine.indexTranscript(transcript) }

        val memory = MemoryItemEntity(
            memoryId = memId,
            category = category,
            title = cleanTitle,
            content = cleanContent,
            sourceTranscriptId = trId,
            sourceSegmentId = segId,
            sessionId = "manual_notes",
            sourceTimestampMs = now,
            confidence = 1.0f,
            certaintyLevel = CertaintyLevel.EXPLICIT,
            entitiesCsv = "",
            extractedAtMs = now,
            isArchived = false,
            isPinned = pin,
            updatedAtMs = now
        )
        memoryDao.insertMemories(listOf(memory))
        runCatching { searchEngine.indexMemory(memory) }

        if (createTask || category == MemoryCategory.TASK || category == MemoryCategory.COMMITMENT || category == MemoryCategory.DEADLINE) {
            promoteMemoryToTask(memory, priority = if (pin) 2 else 1)
        }
        memory
    }

    suspend fun generateProductivityBrief(insights: SecondMindInsightsSnapshot): Result<String> {
        val memories = memoryDao.getActiveMemories(30)
        val openTasks = memoryDao.getOpenTasks()
        if (memories.isEmpty() && openTasks.isEmpty()) {
            return Result.failure(IllegalStateException("Capture a few thoughts or sync speech from Device B first."))
        }

        val sources = buildList {
            var idx = 1
            for (t in openTasks.take(8)) {
                add(
                    GroundedSourceSnippet(
                        citationTag = "T${idx++}",
                        transcriptId = t.sourceTranscriptId,
                        memoryId = t.memoryId,
                        timestampMs = t.createdAtMs,
                        formattedTime = searchEngine.formatTimestamp(t.createdAtMs),
                        category = MemoryCategory.TASK,
                        text = "[OPEN TASK] ${t.title}: ${t.notes}"
                    )
                )
            }
            for (m in memories.take(14)) {
                add(
                    GroundedSourceSnippet(
                        citationTag = "S${idx++}",
                        transcriptId = m.sourceTranscriptId,
                        memoryId = m.memoryId,
                        timestampMs = m.sourceTimestampMs,
                        formattedTime = searchEngine.formatTimestamp(m.sourceTimestampMs),
                        category = m.category,
                        certaintyLevel = m.certaintyLevel,
                        text = "${m.title}: ${m.content}"
                    )
                )
            }
        }

        val statsContext = buildString {
            appendLine("Today: ${insights.todayClipsCount} audio clips (${insights.todaySpokenSeconds}s), ${insights.todayMemoriesCount} new memories.")
            appendLine("Streak: ${insights.captureStreakDays} day(s) | Pinned: ${insights.pinnedCount} | Open Tasks: ${insights.openTasksCount} | Completed: ${insights.completedTasksCount}.")
            if (insights.cleanTopEntities.isNotEmpty()) {
                appendLine("Top Focus Entities: ${insights.cleanTopEntities.take(6).joinToString(", ") { it.name }}")
            }
        }

        val provider = resolveActiveLlmProvider()
        if (provider === uoaiLlm) {
            val aiRes = uoaiLlm.generateProductivityBrief(statsContext, sources)
            if (aiRes.isSuccess) return aiRes
        }

        // Deterministic offline fallback brief
        val fallback = buildString {
            appendLine("**Focus Themes**")
            val themes = insights.topCategories.take(3).joinToString(", ") { "${it.category.name} (${it.count})" }
            appendLine("- Active focus areas: ${themes.ifBlank { "General capture" }}.")
            appendLine()
            appendLine("**Priority Next Actions**")
            if (openTasks.isEmpty()) {
                appendLine("- All caught up — zero open tasks pending.")
            } else {
                openTasks.take(4).forEach { appendLine("- [ ] ${it.title}") }
            }
            appendLine()
            appendLine("**Quick 2nd-Mind Nudge**")
            appendLine("- Pin your highest-leverage decisions in the Vault and review open commitments before ending your day.")
        }
        return Result.success(fallback.trim())
    }

    fun clearSearchCache() {
        searchEngine.clearVectorCache()
    }

    suspend fun searchMemories(query: String, filter: SearchFilter): List<ScoredMemoryResult> {
        return searchEngine.searchMemoriesHybrid(query, filter)
    }

    private suspend fun resolveActiveLlmProvider(): LlmProvider {
        val settings = settingsRepo.getSettings()
        return when (settings.llmProviderType) {
            LlmProviderType.UOAI_OFFLINE -> uoaiLlm
            LlmProviderType.LOCAL_HEURISTIC_OFFLINE -> offlineLlm
        }
    }

    private data class SummaryScope(
        val startMs: Long,
        val endMs: Long,
        val label: String,
        val transcripts: List<TranscriptEntity>,
        val memories: List<MemoryItemEntity>
    )
}
