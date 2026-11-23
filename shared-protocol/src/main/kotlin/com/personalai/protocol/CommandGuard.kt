package com.personalai.protocol

import kotlin.math.abs

data class CachedCommandOutcome(
    val commandId: String,
    val sequenceNumber: Long,
    val expiresAtMs: Long,
    val ack: AcknowledgementPayload
)

interface CommandDedupeStore {
    suspend fun find(commandId: String): CachedCommandOutcome?
    suspend fun save(outcome: CachedCommandOutcome)
    suspend fun pruneExpired(nowMs: Long)
}

class InMemoryCommandDedupeStore : CommandDedupeStore {
    private val map = LinkedHashMap<String, CachedCommandOutcome>()
    private val lock = Any()

    override suspend fun find(commandId: String): CachedCommandOutcome? = synchronized(lock) {
        map[commandId]
    }

    override suspend fun save(outcome: CachedCommandOutcome) = synchronized(lock) {
        map[outcome.commandId] = outcome
    }

    override suspend fun pruneExpired(nowMs: Long) = synchronized(lock) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expiresAtMs < nowMs) it.remove()
        }
    }
}

sealed interface CommandValidationResult {
    data class Execute(val envelope: ProtocolEnvelope, val commandType: CommandType) : CommandValidationResult
    data class DuplicateCached(val cachedAck: AcknowledgementPayload) : CommandValidationResult
    data class Rejected(val errorCode: ProtocolErrorCode, val reason: String) : CommandValidationResult
}

class CommandReplayGuard(
    private val dedupeStore: CommandDedupeStore,
    private val clockSkewGraceMs: Long = 5_000L
) {
    private var lastAcceptedSequence: Long = -1L
    private var estimatedClientMinusServerSkewMs: Long = 0L

    fun resetSessionSequence() {
        lastAcceptedSequence = -1L
        estimatedClientMinusServerSkewMs = 0L
    }

    fun calibrateClockSkew(clientTimeMs: Long, serverTimeMs: Long) {
        estimatedClientMinusServerSkewMs = clientTimeMs - serverTimeMs
    }

    suspend fun validateIncoming(
        envelope: ProtocolEnvelope,
        sessionHmacKey: ByteArray?,
        sessionContext: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
        allowPreAuthCommands: Boolean = false
    ): CommandValidationResult {
        if (envelope.protocolVersion != BleProtocolConstants.PROTOCOL_VERSION) {
            return CommandValidationResult.Rejected(
                ProtocolErrorCode.UNSUPPORTED_VERSION,
                "Unsupported protocol version ${envelope.protocolVersion}"
            )
        }

        val commandType = CommandType.fromOpCode(envelope.opCode)
            ?: return CommandValidationResult.Rejected(
                ProtocolErrorCode.INVALID_ARGUMENT,
                "Unknown command opCode=${envelope.opCode}"
            )

        val isPreAuthCommand = commandType == CommandType.GET_DEVICE_INFO ||
            commandType == CommandType.AUTH_VERIFY ||
            commandType == CommandType.PAIR_EXCHANGE

        if (!isPreAuthCommand || !allowPreAuthCommands) {
            if (sessionHmacKey == null || !envelope.verifyHmac(sessionHmacKey, sessionContext)) {
                return CommandValidationResult.Rejected(
                    ProtocolErrorCode.AUTH_FAILED,
                    "Missing or invalid session HMAC authentication tag"
                )
            }
        }

        // Check idempotent duplicate command before sequence/TTL rejection so bounded retries succeed cleanly.
        val cached = dedupeStore.find(envelope.commandId)
        if (cached != null) {
            return CommandValidationResult.DuplicateCached(cached.ack)
        }

        if (!isPreAuthCommand) {
            val ttl = envelope.ttlMs.toLong().coerceAtLeast(1_000L)
            val adjustedClientTimeMs = envelope.timestampMs - estimatedClientMinusServerSkewMs
            val ageMs = abs(nowMs - adjustedClientTimeMs)
            if (ageMs > ttl + clockSkewGraceMs) {
                return CommandValidationResult.Rejected(
                    ProtocolErrorCode.EXPIRED,
                    "Command expired (age=${ageMs}ms, ttl=${ttl}ms)"
                )
            }

            if (envelope.sequenceNumber <= lastAcceptedSequence) {
                return CommandValidationResult.Rejected(
                    ProtocolErrorCode.REPLAY_DETECTED,
                    "Stale sequenceNumber=${envelope.sequenceNumber} <= last=$lastAcceptedSequence"
                )
            }
            lastAcceptedSequence = envelope.sequenceNumber
        }

        return CommandValidationResult.Execute(envelope, commandType)
    }

    suspend fun recordOutcome(
        envelope: ProtocolEnvelope,
        ack: AcknowledgementPayload,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val retentionMs = maxOf(envelope.ttlMs.toLong(), 300_000L)
        dedupeStore.save(
            CachedCommandOutcome(
                commandId = envelope.commandId,
                sequenceNumber = envelope.sequenceNumber,
                expiresAtMs = nowMs + retentionMs,
                ack = ack
            )
        )
        dedupeStore.pruneExpired(nowMs)
    }
}
