# Data Model Specification

## 1. Device B (`recorder-app`) — Room Schema (`recorder_db`)

### `RecorderDeviceEntity` (`recorder_devices`)
- `deviceId: String` (PK, immutable UUIDv4)
- `deviceName: String`
- `firmwareVersion: String`
- `pairedDeviceId: String?`
- `pairedDeviceBondAddress: String?` (diagnostic hint only; never sole identity)
- `createdAt: Long`
- `lastConnectedAt: Long?`

### `RecordingSessionEntity` (`recording_sessions`)
- `sessionId: String` (PK, UUIDv4)
- `startTime: Long`
- `endTime: Long?`
- `status: SessionStatus` (`ACTIVE`, `PAUSED`, `COMPLETED`, `INTERRUPTED`, `FAILED`)
- `interruptionCount: Int`
- `totalSegments: Int`
- `terminationReason: String?`

### `AudioSegmentEntity` (`audio_segments`)
- `segmentId: String` (PK, UUIDv4)
- `sessionId: String` (FK → `recording_sessions`)
- `sequenceNumber: Long` (unique monotonic sequence across device lifetime)
- `startTimestamp: Long`
- `endTimestamp: Long`
- `durationMs: Long`
- `filePath: String` (app-private relative `.enc` path)
- `fileSize: Long` (plaintext WAV byte length)
- `encryptedSize: Long` (ciphertext `.enc` byte length)
- `checksum: String` (SHA-256 hex of plaintext WAV)
- `uploadStatus: UploadStatus` (`LOCAL_ONLY`, `QUEUED`, `IN_PROGRESS`, `TRANSFERRED_UNVERIFIED`, `ACKED`, `QUARANTINED`)
- `createdAt: Long`

### `TranscriptSegmentEntity` (`transcript_segments`)
- `transcriptId: String` (PK)
- `segmentId: String` (FK → `audio_segments`, unique)
- `text: String`
- `language: String`
- `confidence: Float`
- `processingStatus: TranscriptProcessingStatus`

### `SyncQueueEntity` (`sync_queue`)
- `itemId: String` (PK)
- `segmentId: String` (FK → `audio_segments`, unique)
- `syncStatus: SyncItemStatus` (`PENDING`, `SENDING`, `AWAITING_ACK`, `ACKED`, `FAILED`)
- `bytesTransferred: Long`
- `retryCount: Int`
- `lastAttemptAt: Long?`
- `lastError: String?`

### `ProcessedCommandEntity` (`processed_commands`) & `DiagnosticEventEntity` (`diagnostic_events`)
- `ProcessedCommandEntity`: `commandId` (PK), `sequenceNumber`, `receivedAt`, `expiresAt`, `resultCode`, `responsePayloadBase64`.
- `DiagnosticEventEntity`: `eventId` (PK), `timestamp`, `category`, `severity`, `recorderState`, `bleState`, `message`, `metadataJson`.

## 2. Encrypted Audio Container Format (`.enc`)
- Magic header (`"PAIE"`, 4 bytes) + Format version (`1`, 1 byte) + Base IV (`12` bytes) + Chunk size (`65536`, 4 bytes) + Segment metadata header length + sequence of AES-256-GCM encrypted chunks (`ciphertext || 16-byte GCM tag`).
- Per-chunk nonce = `baseIv XOR chunkIndex` (big-endian in trailing 8 bytes) with AAD = `segmentId|sessionId|sequenceNumber|chunkIndex`.
- Written atomically via `.enc.tmp` + flush/sync + rename; verified on read by recomputing SHA-256 over decrypted WAV bytes.

## 3. Device A (`primary-app`) — Implemented Room Schema v4 (`primary_db`)
- **v1:** `PairedRecorderEntity` (`paired_recorders`), `CommandAuditEntity` (`command_audit`).
- **v2 (`MIGRATION_1_2`):** `SyncedSessionEntity` (`synced_sessions`), `SyncedSegmentEntity` (`synced_segments`), `TranscriptEntity` (`transcripts`), `TranscriptFtsEntity` (`transcripts_fts` `@Fts4`).
- **v3 (`MIGRATION_2_3`):** Added `memoryExtractionStatus` on `transcripts`, plus:
  - `MemoryItemEntity` (`memory_items` — 13 PRD categories, `CertaintyLevel`, `confidence`, `sourceTranscriptId` CASCADE FK, `entitiesCsv`, `isArchived`)
  - `EntityNodeEntity` (`entity_nodes`) & `MemoryEntityCrossRef` (`memory_entity_cross_ref`)
  - `TaskReminderEntity` (`task_reminders` — `OPEN`/`DONE`/`DISMISSED`, `dueAtMs`, `dueLabel`, `notificationScheduled`)
  - `SummaryEntity` (`summaries` — `CONVERSATION`/`DAILY`/`WEEKLY`/`MEETING` Markdown + structured JSON lists)
  - `EmbeddingVectorEntity` (`embedding_vectors` — 256-dim `float32` BLOB vectors per `TRANSCRIPT`/`MEMORY`)
  - `AskAiAuditEntity` (`ask_ai_audit` — grounded Q&A history and cited transcript/memory IDs)
- **v4 (`MIGRATION_3_4` — 2nd Mind Memory & Audio Retention):**
  - `synced_segments`: added `audioDeleted` (`DEFAULT 0`), `audioDeletedAtMs`, and composite index `(audioDeleted, startTimestampMs)` so 1/3/5-day audio chunk pruning reclaims encrypted WAV storage while keeping transcripts & memories permanently intact.
  - `memory_items`: added `isPinned` (`DEFAULT 0`), `updatedAtMs` (`DEFAULT 0`), and composite index `(isArchived, isPinned, sourceTimestampMs)` for instant Pinned/Active/Archived Vault sorting and editable memories.
  - `task_reminders`: added `priority` (`DEFAULT 1`: 0=Low, 1=Normal, 2=High).
