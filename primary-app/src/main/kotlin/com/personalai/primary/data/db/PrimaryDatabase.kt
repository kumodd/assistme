package com.personalai.primary.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personalai.protocol.CommandType
import com.personalai.protocol.ProtocolErrorCode
import com.personalai.protocol.RecorderState
import kotlinx.coroutines.flow.Flow

enum class AsrProcessingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}

enum class MemoryExtractionStatus {
    PENDING,
    COMPLETED,
    FAILED
}

enum class MemoryCategory {
    FACT,
    PREFERENCE,
    DECISION,
    COMMITMENT,
    TASK,
    DEADLINE,
    EVENT,
    IDEA,
    QUESTION,
    ENTITY,
    PROJECT,
    MEETING_NOTE,
    FOLLOW_UP
}

enum class CertaintyLevel {
    EXPLICIT,
    INFERRED_MEDIUM,
    UNCERTAIN
}

enum class EntityNodeType {
    PERSON,
    PROJECT,
    ORGANIZATION,
    PLACE,
    TOPIC
}

enum class TaskReminderStatus {
    OPEN,
    DONE,
    DISMISSED
}

enum class SummaryType {
    CONVERSATION,
    DAILY,
    WEEKLY,
    MEETING
}

enum class EmbeddingOwnerType {
    TRANSCRIPT,
    MEMORY
}

@Entity(tableName = "paired_recorders")
data class PairedRecorderEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val bleAddress: String,
    val firmwareVersion: String,
    val pairedAtMs: Long,
    val lastConnectedAtMs: Long? = null,
    val lastSyncTimestampMs: Long? = null,
    val lastKnownRecorderState: RecorderState = RecorderState.UNCONFIGURED,
    val lastBatteryPct: Int = -1,
    val lastFreeStorageBytes: Long = 0L,
    val lastTotalStorageBytes: Long = 0L,
    val lastPendingSyncCount: Int = 0
)

@Entity(tableName = "command_audit")
data class CommandAuditEntity(
    @PrimaryKey val commandId: String,
    val commandType: CommandType,
    val sentAtMs: Long,
    val success: Boolean,
    val errorCode: ProtocolErrorCode,
    val message: String,
    val rttMs: Long
)

@Entity(
    tableName = "synced_sessions",
    indices = [Index(value = ["lastSegmentAtMs"])]
)
data class SyncedSessionEntity(
    @PrimaryKey val sessionId: String,
    val deviceId: String,
    val firstSegmentAtMs: Long,
    val lastSegmentAtMs: Long,
    val segmentCount: Int
)

@Entity(
    tableName = "synced_segments",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sequenceNumber"]),
        Index(value = ["asrStatus"]),
        Index(value = ["startTimestampMs"]),
        Index(value = ["audioDeleted", "startTimestampMs"])
    ]
)
data class SyncedSegmentEntity(
    @PrimaryKey val segmentId: String,
    val sessionId: String,
    val sequenceNumber: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val durationMs: Long,
    val localEncryptedAudioPath: String,
    val wavSizeBytes: Long,
    val checksum: String,
    val syncedAtMs: Long,
    val asrStatus: AsrProcessingStatus = AsrProcessingStatus.PENDING,
    val asrError: String? = null,
    @ColumnInfo(defaultValue = "0") val audioDeleted: Boolean = false,
    val audioDeletedAtMs: Long? = null
)

@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(
            entity = SyncedSegmentEntity::class,
            parentColumns = ["segmentId"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["segmentId"], unique = true),
        Index(value = ["sessionId"]),
        Index(value = ["language"]),
        Index(value = ["startTimeMs"])
    ]
)
data class TranscriptEntity(
    @PrimaryKey val transcriptId: String,
    val segmentId: String,
    val sessionId: String,
    val text: String,
    val language: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val confidence: Float,
    val speakerLabel: String? = null,
    val asrProvider: String,
    val createdAtMs: Long,
    val memoryExtractionStatus: MemoryExtractionStatus = MemoryExtractionStatus.PENDING
)

@Fts4(contentEntity = TranscriptEntity::class)
@Entity(tableName = "transcripts_fts")
data class TranscriptFtsEntity(
    val text: String,
    val language: String
)

@Entity(
    tableName = "memory_items",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptEntity::class,
            parentColumns = ["transcriptId"],
            childColumns = ["sourceTranscriptId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sourceTranscriptId"]),
        Index(value = ["sessionId"]),
        Index(value = ["category"]),
        Index(value = ["sourceTimestampMs"]),
        Index(value = ["isArchived", "isPinned", "sourceTimestampMs"])
    ]
)
data class MemoryItemEntity(
    @PrimaryKey val memoryId: String,
    val category: MemoryCategory,
    val title: String,
    val content: String,
    val sourceTranscriptId: String,
    val sourceSegmentId: String,
    val sessionId: String,
    val sourceTimestampMs: Long,
    val confidence: Float,
    val certaintyLevel: CertaintyLevel,
    val entitiesCsv: String = "",
    val extractedAtMs: Long,
    val isArchived: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0L
)

@Entity(
    tableName = "entity_nodes",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class EntityNodeEntity(
    @PrimaryKey val entityId: String,
    val name: String,
    val normalizedName: String,
    val entityType: EntityNodeType,
    val mentionCount: Int = 1,
    val lastMentionedMs: Long
)

@Entity(
    tableName = "memory_entity_cross_ref",
    primaryKeys = ["memoryId", "entityId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryItemEntity::class,
            parentColumns = ["memoryId"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EntityNodeEntity::class,
            parentColumns = ["entityId"],
            childColumns = ["entityId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["entityId"])]
)
data class MemoryEntityCrossRef(
    val memoryId: String,
    val entityId: String
)

@Entity(
    tableName = "task_reminders",
    foreignKeys = [
        ForeignKey(
            entity = MemoryItemEntity::class,
            parentColumns = ["memoryId"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["memoryId"]),
        Index(value = ["status"]),
        Index(value = ["dueAtMs"])
    ]
)
data class TaskReminderEntity(
    @PrimaryKey val taskId: String,
    val memoryId: String?,
    val sourceTranscriptId: String,
    val title: String,
    val notes: String,
    val dueAtMs: Long? = null,
    val dueLabel: String? = null,
    val status: TaskReminderStatus = TaskReminderStatus.OPEN,
    val notificationScheduled: Boolean = false,
    val createdAtMs: Long,
    @ColumnInfo(defaultValue = "1") val priority: Int = 1
)

@Entity(
    tableName = "summaries",
    indices = [
        Index(value = ["summaryType"]),
        Index(value = ["periodEndMs"]),
        Index(value = ["sessionId"])
    ]
)
data class SummaryEntity(
    @PrimaryKey val summaryId: String,
    val summaryType: SummaryType,
    val title: String,
    val periodStartMs: Long,
    val periodEndMs: Long,
    val sessionId: String? = null,
    val markdownBody: String,
    val keyDecisionsJson: String = "[]",
    val pendingTasksJson: String = "[]",
    val followUpsJson: String = "[]",
    val sourceTranscriptIdsJson: String = "[]",
    val createdAtMs: Long
)

@Entity(
    tableName = "embedding_vectors",
    indices = [Index(value = ["ownerType", "ownerId", "modelId"], unique = true)]
)
data class EmbeddingVectorEntity(
    @PrimaryKey val vectorId: String,
    val ownerType: EmbeddingOwnerType,
    val ownerId: String,
    val modelId: String,
    val dimensions: Int,
    val vectorBlob: ByteArray,
    val updatedAtMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingVectorEntity) return false
        return vectorId == other.vectorId &&
            ownerType == other.ownerType &&
            ownerId == other.ownerId &&
            modelId == other.modelId &&
            dimensions == other.dimensions &&
            vectorBlob.contentEquals(other.vectorBlob) &&
            updatedAtMs == other.updatedAtMs
    }

    override fun hashCode(): Int {
        var result = vectorId.hashCode()
        result = 31 * result + ownerType.hashCode()
        result = 31 * result + ownerId.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + dimensions
        result = 31 * result + vectorBlob.contentHashCode()
        result = 31 * result + updatedAtMs.hashCode()
        return result
    }
}

@Entity(
    tableName = "ask_ai_audit",
    indices = [Index(value = ["createdAtMs"])]
)
data class AskAiAuditEntity(
    @PrimaryKey val queryId: String,
    val question: String,
    val answer: String,
    val citedTranscriptIdsJson: String,
    val citedMemoryIdsJson: String,
    val providerUsed: String,
    val createdAtMs: Long
)

data class SegmentWithTranscript(
    @Embedded val segment: SyncedSegmentEntity,
    @Relation(
        parentColumn = "segmentId",
        entityColumn = "segmentId"
    )
    val transcript: TranscriptEntity?
)

class PrimaryTypeConverters {
    @TypeConverter fun fromRecorderState(value: RecorderState): String = value.name
    @TypeConverter fun toRecorderState(value: String): RecorderState = RecorderState.valueOf(value)

    @TypeConverter fun fromCommandType(value: CommandType): String = value.name
    @TypeConverter fun toCommandType(value: String): CommandType = CommandType.valueOf(value)

    @TypeConverter fun fromErrorCode(value: ProtocolErrorCode): String = value.name
    @TypeConverter fun toErrorCode(value: String): ProtocolErrorCode = ProtocolErrorCode.valueOf(value)

    @TypeConverter fun fromAsrStatus(value: AsrProcessingStatus): String = value.name
    @TypeConverter fun toAsrStatus(value: String): AsrProcessingStatus = AsrProcessingStatus.valueOf(value)

    @TypeConverter fun fromMemExtStatus(value: MemoryExtractionStatus): String = value.name
    @TypeConverter fun toMemExtStatus(value: String): MemoryExtractionStatus = MemoryExtractionStatus.valueOf(value)

    @TypeConverter fun fromMemoryCategory(value: MemoryCategory): String = value.name
    @TypeConverter fun toMemoryCategory(value: String): MemoryCategory = MemoryCategory.valueOf(value)

    @TypeConverter fun fromCertainty(value: CertaintyLevel): String = value.name
    @TypeConverter fun toCertainty(value: String): CertaintyLevel = CertaintyLevel.valueOf(value)

    @TypeConverter fun fromEntityType(value: EntityNodeType): String = value.name
    @TypeConverter fun toEntityType(value: String): EntityNodeType = EntityNodeType.valueOf(value)

    @TypeConverter fun fromTaskStatus(value: TaskReminderStatus): String = value.name
    @TypeConverter fun toTaskStatus(value: String): TaskReminderStatus = TaskReminderStatus.valueOf(value)

    @TypeConverter fun fromSummaryType(value: SummaryType): String = value.name
    @TypeConverter fun toSummaryType(value: String): SummaryType = SummaryType.valueOf(value)

    @TypeConverter fun fromEmbeddingOwner(value: EmbeddingOwnerType): String = value.name
    @TypeConverter fun toEmbeddingOwner(value: String): EmbeddingOwnerType = EmbeddingOwnerType.valueOf(value)
}

@Dao
interface PairedRecorderDao {
    @Query("SELECT * FROM paired_recorders LIMIT 1")
    fun observePairedDevice(): Flow<PairedRecorderEntity?>

    @Query("SELECT * FROM paired_recorders LIMIT 1")
    suspend fun getPairedDevice(): PairedRecorderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: PairedRecorderEntity)

    @Query("DELETE FROM paired_recorders")
    suspend fun deleteAll()
}

@Dao
interface CommandAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CommandAuditEntity)

    @Query("SELECT * FROM command_audit ORDER BY sentAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 40): Flow<List<CommandAuditEntity>>

    @Query("DELETE FROM command_audit WHERE commandId NOT IN (SELECT commandId FROM command_audit ORDER BY sentAtMs DESC LIMIT :keepCount)")
    suspend fun pruneKeepingLatest(keepCount: Int = 500)

    @Query("DELETE FROM command_audit")
    suspend fun deleteAll()
}

@Dao
interface SyncedContentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SyncedSessionEntity)

    @Query("SELECT * FROM synced_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): SyncedSessionEntity?

    @Query("SELECT * FROM synced_sessions ORDER BY lastSegmentAtMs DESC")
    fun observeSessions(): Flow<List<SyncedSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSegment(segment: SyncedSegmentEntity): Long

    @Query("SELECT * FROM synced_segments WHERE segmentId = :segmentId LIMIT 1")
    suspend fun getSegment(segmentId: String): SyncedSegmentEntity?

    @Query("SELECT * FROM synced_segments WHERE asrStatus = 'PENDING' ORDER BY sequenceNumber ASC")
    suspend fun getPendingAsrSegments(): List<SyncedSegmentEntity>

    @Query("UPDATE synced_segments SET asrStatus = :status, asrError = :error WHERE segmentId = :segmentId")
    suspend fun updateAsrStatus(segmentId: String, status: AsrProcessingStatus, error: String?)

    @Query("UPDATE synced_segments SET asrStatus = 'PENDING', asrError = NULL WHERE asrStatus = 'FAILED'")
    suspend fun resetFailedAsrToPending()

    @Query("UPDATE synced_segments SET asrStatus = 'PENDING', asrError = NULL WHERE asrStatus = 'PROCESSING'")
    suspend fun resetInterruptedProcessingToPending()

    @androidx.room.Transaction
    @Query("SELECT * FROM synced_segments ORDER BY startTimestampMs DESC LIMIT :limit")
    fun observeSegmentsWithTranscripts(limit: Int = 200): Flow<List<SegmentWithTranscript>>

    @Query("SELECT COUNT(*) FROM synced_segments")
    fun observeTotalSyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM synced_segments WHERE asrStatus = 'PENDING' OR asrStatus = 'PROCESSING'")
    fun observePendingAsrCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM synced_segments
        WHERE audioDeleted = 0
          AND asrStatus = 'COMPLETED'
          AND MAX(syncedAtMs, startTimestampMs) < :cutoffMs
          AND EXISTS (
              SELECT 1 FROM transcripts t
              WHERE t.segmentId = synced_segments.segmentId
                AND TRIM(t.text) != ''
                AND t.text != '[Inaudible / No Speech Text]'
          )
        ORDER BY syncedAtMs ASC
        """
    )
    suspend fun getRetentionEligibleAudioSegments(cutoffMs: Long): List<SyncedSegmentEntity>

    @Query(
        """
        SELECT * FROM synced_segments
        WHERE audioDeleted = 0
          AND asrStatus = 'COMPLETED'
          AND EXISTS (
              SELECT 1 FROM transcripts t
              WHERE t.segmentId = synced_segments.segmentId
                AND TRIM(t.text) != ''
                AND t.text != '[Inaudible / No Speech Text]'
          )
        ORDER BY syncedAtMs ASC
        """
    )
    suspend fun getAllVerifiedTranscribedAudioSegments(): List<SyncedSegmentEntity>

    @Query(
        """
        SELECT EXISTS (
            SELECT 1 FROM synced_segments s
            INNER JOIN transcripts t ON t.segmentId = s.segmentId
            WHERE s.segmentId = :segmentId
              AND s.audioDeleted = 0
              AND s.asrStatus = 'COMPLETED'
              AND TRIM(t.text) != ''
              AND t.text != '[Inaudible / No Speech Text]'
        )
        """
    )
    suspend fun isSegmentVerifiedTranscribed(segmentId: String): Boolean

    @Query("SELECT COUNT(*) FROM synced_segments WHERE audioDeleted = 0")
    fun observeActiveAudioChunkCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(wavSizeBytes), 0) FROM synced_segments WHERE audioDeleted = 0")
    fun observeActiveAudioBytes(): Flow<Long>

    @Query("SELECT COALESCE(MIN(sequenceNumber), 0) FROM synced_segments")
    suspend fun getMinSequenceNumber(): Long

    @Query("UPDATE synced_segments SET audioDeleted = 1, audioDeletedAtMs = :deletedAtMs WHERE segmentId = :segmentId")
    suspend fun markAudioDeleted(segmentId: String, deletedAtMs: Long)

    @Query("DELETE FROM synced_segments")
    suspend fun deleteAllSegments()

    @Query("DELETE FROM synced_sessions")
    suspend fun deleteAllSessions()
}

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transcript: TranscriptEntity)

    @Query("SELECT * FROM transcripts WHERE transcriptId = :id LIMIT 1")
    suspend fun getById(id: String): TranscriptEntity?

    @Query("SELECT * FROM transcripts WHERE segmentId = :segmentId LIMIT 1")
    suspend fun getBySegmentId(segmentId: String): TranscriptEntity?

    @Query(
        """
        UPDATE transcripts
        SET text = :text,
            language = :language,
            confidence = :confidence,
            asrProvider = :asrProvider,
            createdAtMs = :createdAtMs
        WHERE transcriptId = :transcriptId
        """
    )
    suspend fun updateInPlace(
        transcriptId: String,
        text: String,
        language: String,
        confidence: Float,
        asrProvider: String,
        createdAtMs: Long
    )

    @Query("SELECT * FROM transcripts WHERE transcriptId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts ORDER BY startTimeMs DESC LIMIT :limit")
    fun observeRecentTranscripts(limit: Int = 100): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts ORDER BY startTimeMs DESC LIMIT :limit")
    suspend fun getRecentTranscripts(limit: Int = 200): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE startTimeMs BETWEEN :startMs AND :endMs ORDER BY startTimeMs ASC")
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE sessionId = :sessionId ORDER BY startTimeMs ASC")
    suspend fun getBySession(sessionId: String): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE memoryExtractionStatus = 'PENDING' ORDER BY startTimeMs ASC")
    suspend fun getPendingMemoryExtraction(): List<TranscriptEntity>

    @Query("UPDATE transcripts SET memoryExtractionStatus = :status WHERE transcriptId = :transcriptId")
    suspend fun updateMemoryExtractionStatus(transcriptId: String, status: MemoryExtractionStatus)

    @Query("UPDATE transcripts SET memoryExtractionStatus = 'PENDING' WHERE memoryExtractionStatus = 'FAILED'")
    suspend fun resetFailedExtractionToPending()

    @Query(
        """
        SELECT transcripts.* FROM transcripts
        JOIN transcripts_fts ON transcripts.rowid = transcripts_fts.rowid
        WHERE transcripts_fts MATCH :ftsQuery
        ORDER BY transcripts.startTimeMs DESC
        """
    )
    suspend fun searchFts(ftsQuery: String): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE text LIKE '%' || :query || '%' ORDER BY startTimeMs DESC LIMIT 100")
    suspend fun searchSubstring(query: String): List<TranscriptEntity>

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemories(items: List<MemoryItemEntity>)

    @Query("SELECT * FROM memory_items WHERE isArchived = 0 ORDER BY isPinned DESC, sourceTimestampMs DESC LIMIT :limit")
    fun observeActiveMemories(limit: Int = 300): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items WHERE isArchived = 1 ORDER BY sourceTimestampMs DESC LIMIT :limit")
    fun observeArchivedMemories(limit: Int = 200): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items WHERE isArchived = 0 ORDER BY isPinned DESC, sourceTimestampMs DESC LIMIT :limit")
    suspend fun getActiveMemories(limit: Int = 500): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE memoryId = :memoryId LIMIT 1")
    suspend fun getById(memoryId: String): MemoryItemEntity?

    @Query("SELECT * FROM memory_items WHERE memoryId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE sourceTimestampMs BETWEEN :startMs AND :endMs AND isArchived = 0 ORDER BY isPinned DESC, sourceTimestampMs DESC")
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<MemoryItemEntity>

    @Query("DELETE FROM memory_items WHERE sourceTranscriptId = :transcriptId")
    suspend fun deleteBySourceTranscript(transcriptId: String)

    @Query("UPDATE memory_items SET isArchived = 1, isPinned = 0, updatedAtMs = :updatedAtMs WHERE memoryId = :memoryId")
    suspend fun archiveMemory(memoryId: String, updatedAtMs: Long = System.currentTimeMillis())

    @Query("UPDATE memory_items SET isArchived = 0, updatedAtMs = :updatedAtMs WHERE memoryId = :memoryId")
    suspend fun restoreMemory(memoryId: String, updatedAtMs: Long = System.currentTimeMillis())

    @Query("UPDATE memory_items SET isPinned = :pinned, updatedAtMs = :updatedAtMs WHERE memoryId = :memoryId")
    suspend fun setMemoryPinned(memoryId: String, pinned: Boolean, updatedAtMs: Long = System.currentTimeMillis())

    @Query("UPDATE memory_items SET title = :title, content = :content, category = :category, updatedAtMs = :updatedAtMs WHERE memoryId = :memoryId")
    suspend fun updateMemoryContent(
        memoryId: String,
        title: String,
        content: String,
        category: MemoryCategory,
        updatedAtMs: Long
    )

    @Query("DELETE FROM memory_items WHERE memoryId = :memoryId")
    suspend fun deleteMemoryById(memoryId: String)

    @Query("DELETE FROM entity_nodes WHERE normalizedName IN (:names)")
    suspend fun deleteEntitiesByNormalizedNames(names: List<String>)

    @Query("DELETE FROM memory_items")
    suspend fun deleteAllMemories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntityNode(entity: EntityNodeEntity)

    @Query("SELECT * FROM entity_nodes WHERE normalizedName = :normalized LIMIT 1")
    suspend fun findEntityByNormalizedName(normalized: String): EntityNodeEntity?

    @Query("SELECT * FROM entity_nodes ORDER BY mentionCount DESC, lastMentionedMs DESC LIMIT :limit")
    fun observeTopEntities(limit: Int = 50): Flow<List<EntityNodeEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemoryEntityRefs(refs: List<MemoryEntityCrossRef>)

    @Query("DELETE FROM entity_nodes")
    suspend fun deleteAllEntities()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TaskReminderEntity>)

    @Query("SELECT * FROM task_reminders ORDER BY CASE status WHEN 'OPEN' THEN 0 WHEN 'DONE' THEN 1 ELSE 2 END, priority DESC, COALESCE(dueAtMs, 9223372036854775807) ASC, createdAtMs DESC")
    fun observeTasks(): Flow<List<TaskReminderEntity>>

    @Query("SELECT * FROM task_reminders WHERE status = 'OPEN' ORDER BY priority DESC, COALESCE(dueAtMs, 9223372036854775807) ASC")
    suspend fun getOpenTasks(): List<TaskReminderEntity>

    @Query("UPDATE task_reminders SET status = :status WHERE taskId = :taskId")
    suspend fun updateTaskStatus(taskId: String, status: TaskReminderStatus)

    @Query("UPDATE task_reminders SET priority = :priority WHERE taskId = :taskId")
    suspend fun updateTaskPriority(taskId: String, priority: Int)

    @Query("UPDATE task_reminders SET notificationScheduled = 1 WHERE taskId = :taskId")
    suspend fun markTaskNotificationScheduled(taskId: String)

    @Query("DELETE FROM task_reminders")
    suspend fun deleteAllTasks()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: SummaryEntity)

    @Query("SELECT * FROM summaries ORDER BY periodEndMs DESC, createdAtMs DESC LIMIT :limit")
    fun observeSummaries(limit: Int = 60): Flow<List<SummaryEntity>>

    @Query("DELETE FROM summaries")
    suspend fun deleteAllSummaries()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbedding(vector: EmbeddingVectorEntity)

    @Query("SELECT * FROM embedding_vectors WHERE modelId = :modelId")
    suspend fun getEmbeddingsByModel(modelId: String): List<EmbeddingVectorEntity>

    @Query("DELETE FROM embedding_vectors")
    suspend fun deleteAllEmbeddings()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAskAiAudit(audit: AskAiAuditEntity)

    @Query("SELECT * FROM ask_ai_audit ORDER BY createdAtMs DESC LIMIT :limit")
    fun observeAskAiHistory(limit: Int = 40): Flow<List<AskAiAuditEntity>>

    @Query("DELETE FROM ask_ai_audit")
    suspend fun deleteAllAskAiHistory()
}

@Database(
    entities = [
        PairedRecorderEntity::class,
        CommandAuditEntity::class,
        SyncedSessionEntity::class,
        SyncedSegmentEntity::class,
        TranscriptEntity::class,
        TranscriptFtsEntity::class,
        MemoryItemEntity::class,
        EntityNodeEntity::class,
        MemoryEntityCrossRef::class,
        TaskReminderEntity::class,
        SummaryEntity::class,
        EmbeddingVectorEntity::class,
        AskAiAuditEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(PrimaryTypeConverters::class)
abstract class PrimaryDatabase : RoomDatabase() {
    abstract fun pairedRecorderDao(): PairedRecorderDao
    abstract fun commandAuditDao(): CommandAuditDao
    abstract fun syncedContentDao(): SyncedContentDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `synced_sessions` (
                        `sessionId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `firstSegmentAtMs` INTEGER NOT NULL,
                        `lastSegmentAtMs` INTEGER NOT NULL,
                        `segmentCount` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_synced_sessions_lastSegmentAtMs` ON `synced_sessions` (`lastSegmentAtMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `synced_segments` (
                        `segmentId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `sequenceNumber` INTEGER NOT NULL,
                        `startTimestampMs` INTEGER NOT NULL,
                        `endTimestampMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `localEncryptedAudioPath` TEXT NOT NULL,
                        `wavSizeBytes` INTEGER NOT NULL,
                        `checksum` TEXT NOT NULL,
                        `syncedAtMs` INTEGER NOT NULL,
                        `asrStatus` TEXT NOT NULL,
                        `asrError` TEXT,
                        PRIMARY KEY(`segmentId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_synced_segments_sessionId` ON `synced_segments` (`sessionId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_synced_segments_sequenceNumber` ON `synced_segments` (`sequenceNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_synced_segments_asrStatus` ON `synced_segments` (`asrStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_synced_segments_startTimestampMs` ON `synced_segments` (`startTimestampMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transcripts` (
                        `transcriptId` TEXT NOT NULL,
                        `segmentId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `startTimeMs` INTEGER NOT NULL,
                        `endTimeMs` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL,
                        `speakerLabel` TEXT,
                        `asrProvider` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`transcriptId`),
                        FOREIGN KEY(`segmentId`) REFERENCES `synced_segments`(`segmentId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transcripts_segmentId` ON `transcripts` (`segmentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcripts_sessionId` ON `transcripts` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcripts_language` ON `transcripts` (`language`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcripts_startTimeMs` ON `transcripts` (`startTimeMs`)")

                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `transcripts_fts` USING FTS4(
                        `text` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        content=`transcripts`
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transcripts_fts_BEFORE_UPDATE BEFORE UPDATE ON `transcripts` BEGIN DELETE FROM `transcripts_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transcripts_fts_BEFORE_DELETE BEFORE DELETE ON `transcripts` BEGIN DELETE FROM `transcripts_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transcripts_fts_AFTER_UPDATE AFTER UPDATE ON `transcripts` BEGIN INSERT INTO `transcripts_fts`(`docid`, `text`, `language`) VALUES (NEW.`rowid`, NEW.`text`, NEW.`language`); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transcripts_fts_AFTER_INSERT AFTER INSERT ON `transcripts` BEGIN INSERT INTO `transcripts_fts`(`docid`, `text`, `language`) VALUES (NEW.`rowid`, NEW.`text`, NEW.`language`); END")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transcripts` ADD COLUMN `memoryExtractionStatus` TEXT NOT NULL DEFAULT 'PENDING'")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_items` (
                        `memoryId` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `sourceTranscriptId` TEXT NOT NULL,
                        `sourceSegmentId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `sourceTimestampMs` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL,
                        `certaintyLevel` TEXT NOT NULL,
                        `entitiesCsv` TEXT NOT NULL,
                        `extractedAtMs` INTEGER NOT NULL,
                        `isArchived` INTEGER NOT NULL,
                        PRIMARY KEY(`memoryId`),
                        FOREIGN KEY(`sourceTranscriptId`) REFERENCES `transcripts`(`transcriptId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_sourceTranscriptId` ON `memory_items` (`sourceTranscriptId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_sessionId` ON `memory_items` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_category` ON `memory_items` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_sourceTimestampMs` ON `memory_items` (`sourceTimestampMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `entity_nodes` (
                        `entityId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `normalizedName` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `mentionCount` INTEGER NOT NULL,
                        `lastMentionedMs` INTEGER NOT NULL,
                        PRIMARY KEY(`entityId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_entity_nodes_normalizedName` ON `entity_nodes` (`normalizedName`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_entity_cross_ref` (
                        `memoryId` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        PRIMARY KEY(`memoryId`, `entityId`),
                        FOREIGN KEY(`memoryId`) REFERENCES `memory_items`(`memoryId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`entityId`) REFERENCES `entity_nodes`(`entityId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_entity_cross_ref_entityId` ON `memory_entity_cross_ref` (`entityId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_reminders` (
                        `taskId` TEXT NOT NULL,
                        `memoryId` TEXT,
                        `sourceTranscriptId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `dueAtMs` INTEGER,
                        `dueLabel` TEXT,
                        `status` TEXT NOT NULL,
                        `notificationScheduled` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`taskId`),
                        FOREIGN KEY(`memoryId`) REFERENCES `memory_items`(`memoryId`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_reminders_memoryId` ON `task_reminders` (`memoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_reminders_status` ON `task_reminders` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_reminders_dueAtMs` ON `task_reminders` (`dueAtMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `summaries` (
                        `summaryId` TEXT NOT NULL,
                        `summaryType` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `periodStartMs` INTEGER NOT NULL,
                        `periodEndMs` INTEGER NOT NULL,
                        `sessionId` TEXT,
                        `markdownBody` TEXT NOT NULL,
                        `keyDecisionsJson` TEXT NOT NULL,
                        `pendingTasksJson` TEXT NOT NULL,
                        `followUpsJson` TEXT NOT NULL,
                        `sourceTranscriptIdsJson` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`summaryId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_summaries_summaryType` ON `summaries` (`summaryType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_summaries_periodEndMs` ON `summaries` (`periodEndMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_summaries_sessionId` ON `summaries` (`sessionId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `embedding_vectors` (
                        `vectorId` TEXT NOT NULL,
                        `ownerType` TEXT NOT NULL,
                        `ownerId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `dimensions` INTEGER NOT NULL,
                        `vectorBlob` BLOB NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`vectorId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_embedding_vectors_ownerType_ownerId_modelId` ON `embedding_vectors` (`ownerType`, `ownerId`, `modelId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ask_ai_audit` (
                        `queryId` TEXT NOT NULL,
                        `question` TEXT NOT NULL,
                        `answer` TEXT NOT NULL,
                        `citedTranscriptIdsJson` TEXT NOT NULL,
                        `citedMemoryIdsJson` TEXT NOT NULL,
                        `providerUsed` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`queryId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ask_ai_audit_createdAtMs` ON `ask_ai_audit` (`createdAtMs`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `synced_segments` ADD COLUMN `audioDeleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `synced_segments` ADD COLUMN `audioDeletedAtMs` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_synced_segments_audioDeleted_startTimestampMs` ON `synced_segments` (`audioDeleted`, `startTimestampMs`)")

                db.execSQL("ALTER TABLE `memory_items` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `memory_items` ADD COLUMN `updatedAtMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_isArchived_isPinned_sourceTimestampMs` ON `memory_items` (`isArchived`, `isPinned`, `sourceTimestampMs`)")

                db.execSQL("ALTER TABLE `task_reminders` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Replace global UNIQUE(sequenceNumber) with a non-unique index so re-pairing or
                // resetting a Secondary recorder never silently drops segments whose sequence restarts at #1.
                db.execSQL("DROP INDEX IF EXISTS `index_synced_segments_sequenceNumber`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_synced_segments_sequenceNumber` ON `synced_segments` (`sequenceNumber`)")
            }
        }
    }
}
