package com.personalai.recorder.ble

import android.util.Base64
import com.personalai.protocol.AcknowledgementPayload
import com.personalai.protocol.CachedCommandOutcome
import com.personalai.protocol.CommandDedupeStore
import com.personalai.recorder.data.db.ProcessedCommandDao
import com.personalai.recorder.data.db.ProcessedCommandEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomCommandDedupeStore @Inject constructor(
    private val dao: ProcessedCommandDao
) : CommandDedupeStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun find(commandId: String): CachedCommandOutcome? {
        val entity = dao.findById(commandId) ?: return null
        val decodedJson = Base64.decode(entity.responsePayloadBase64, Base64.NO_WRAP)
            .toString(Charsets.UTF_8)
        val ack = runCatching { json.decodeFromString<AcknowledgementPayload>(decodedJson) }.getOrNull()
            ?: return null
        return CachedCommandOutcome(
            commandId = entity.commandId,
            sequenceNumber = entity.sequenceNumber,
            expiresAtMs = entity.expiresAt,
            ack = ack
        )
    }

    override suspend fun save(outcome: CachedCommandOutcome) {
        val encoded = Base64.encodeToString(
            json.encodeToString(outcome.ack).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        dao.upsert(
            ProcessedCommandEntity(
                commandId = outcome.commandId,
                sequenceNumber = outcome.sequenceNumber,
                receivedAt = System.currentTimeMillis(),
                expiresAt = outcome.expiresAtMs,
                resultCode = outcome.ack.errorCode.name,
                responsePayloadBase64 = encoded
            )
        )
    }

    override suspend fun pruneExpired(nowMs: Long) {
        dao.pruneExpired(nowMs)
    }
}
