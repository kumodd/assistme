package com.personalai.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class ProtocolEnvelope(
    val protocolVersion: Int = BleProtocolConstants.PROTOCOL_VERSION,
    val flags: Int = FLAG_REQUIRES_ACK,
    val msgType: BleMessageType,
    val opCode: Byte,
    val commandId: String,
    val sequenceNumber: Long,
    val timestampMs: Long,
    val ttlMs: Int,
    val payload: ByteArray,
    val hmacTag: ByteArray = ByteArray(HMAC_SIZE)
) {
    fun encode(): ByteArray {
        val uuid = UUID.fromString(commandId)
        val total = HEADER_SIZE + payload.size + HMAC_SIZE
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        writeHeaderAndPayload(buf, uuid)
        buf.put(hmacTag.copyOf(HMAC_SIZE))
        return buf.array()
    }

    fun signedWith(hmacKey: ByteArray, sessionContext: ByteArray = ByteArray(0)): ProtocolEnvelope {
        val uuid = UUID.fromString(commandId)
        val body = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        writeHeaderAndPayload(body, uuid)
        val bodyBytes = body.array()
        val macInput = ByteArray(sessionContext.size + bodyBytes.size)
        System.arraycopy(sessionContext, 0, macInput, 0, sessionContext.size)
        System.arraycopy(bodyBytes, 0, macInput, sessionContext.size, bodyBytes.size)
        val tag = ProtocolCrypto.hmacSha256(hmacKey, macInput)
        return copy(hmacTag = tag)
    }

    fun verifyHmac(hmacKey: ByteArray, sessionContext: ByteArray = ByteArray(0)): Boolean {
        if (hmacTag.size != HMAC_SIZE) return false
        val expected = signedWith(hmacKey, sessionContext).hmacTag
        return ProtocolCrypto.constantTimeEquals(expected, hmacTag)
    }

    private fun writeHeaderAndPayload(buf: ByteBuffer, uuid: UUID) {
        buf.putShort(BleProtocolConstants.ENVELOPE_MAGIC)
        buf.put(protocolVersion.toByte())
        buf.put(flags.toByte())
        buf.put(msgType.code)
        buf.put(opCode)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        buf.putLong(sequenceNumber)
        buf.putLong(timestampMs)
        buf.putInt(ttlMs)
        buf.putInt(payload.size)
        buf.put(payload)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtocolEnvelope) return false
        return protocolVersion == other.protocolVersion &&
            flags == other.flags &&
            msgType == other.msgType &&
            opCode == other.opCode &&
            commandId == other.commandId &&
            sequenceNumber == other.sequenceNumber &&
            timestampMs == other.timestampMs &&
            ttlMs == other.ttlMs &&
            payload.contentEquals(other.payload) &&
            hmacTag.contentEquals(other.hmacTag)
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + flags
        result = 31 * result + msgType.hashCode()
        result = 31 * result + opCode
        result = 31 * result + commandId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + ttlMs
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + hmacTag.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 46 // magic(2)+ver(1)+flags(1)+type(1)+op(1)+uuid(16)+seq(8)+ts(8)+ttl(4)+len(4)
        const val HMAC_SIZE = 32
        const val FLAG_REQUIRES_ACK = 0x01
        const val FLAG_AUTHENTICATED = 0x02

        fun decode(bytes: ByteArray): Result<ProtocolEnvelope> = runCatching {
            require(bytes.size >= HEADER_SIZE + HMAC_SIZE) {
                "Envelope too short: ${bytes.size}"
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = buf.short
            require(magic == BleProtocolConstants.ENVELOPE_MAGIC) {
                "Invalid magic: 0x${magic.toInt().and(0xFFFF).toString(16)}"
            }
            val version = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            val msgTypeCode = buf.get()
            val msgType = BleMessageType.fromCode(msgTypeCode)
                ?: throw IllegalArgumentException("Unknown msgType=$msgTypeCode")
            val opCode = buf.get()
            val msb = buf.long
            val lsb = buf.long
            val commandId = UUID(msb, lsb).toString()
            val sequenceNumber = buf.long
            val timestampMs = buf.long
            val ttlMs = buf.int
            val payloadLen = buf.int
            require(payloadLen >= 0 && payloadLen <= 1_048_576) {
                "Invalid payload length: $payloadLen"
            }
            require(bytes.size == HEADER_SIZE + payloadLen + HMAC_SIZE) {
                "Length mismatch: expected=${HEADER_SIZE + payloadLen + HMAC_SIZE}, actual=${bytes.size}"
            }
            val payload = ByteArray(payloadLen)
            buf.get(payload)
            val hmacTag = ByteArray(HMAC_SIZE)
            buf.get(hmacTag)

            ProtocolEnvelope(
                protocolVersion = version,
                flags = flags,
                msgType = msgType,
                opCode = opCode,
                commandId = commandId,
                sequenceNumber = sequenceNumber,
                timestampMs = timestampMs,
                ttlMs = ttlMs,
                payload = payload,
                hmacTag = hmacTag
            )
        }
    }
}
