package com.personalai.primary

import com.personalai.protocol.BleChunkFraming
import com.personalai.protocol.BleMessageType
import com.personalai.protocol.CommandType
import com.personalai.protocol.PacketReassembler
import com.personalai.protocol.ProtocolCrypto
import com.personalai.protocol.ProtocolEnvelope
import com.personalai.protocol.RecorderConfigPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

class PrimaryProtocolSmokeTest {

    @Test
    fun `config command signs, chunks, reassembles, and verifies across primary-app serialization`() {
        val master = ByteArray(32) { 5 }
        val serverNonce = ByteArray(16).also(SecureRandom()::nextBytes)
        val clientNonce = ByteArray(16).also(SecureRandom()::nextBytes)
        val keys = ProtocolCrypto.deriveSessionKeys(master, serverNonce, clientNonce)
        val ctx = serverNonce + clientNonce

        val cfg = RecorderConfigPayload(vadThreshold = 0.62f, localOnlyPauseSync = true)
        val env = ProtocolEnvelope(
            flags = ProtocolEnvelope.FLAG_REQUIRES_ACK or ProtocolEnvelope.FLAG_AUTHENTICATED,
            msgType = BleMessageType.COMMAND,
            opCode = CommandType.SET_CONFIG.opCode,
            commandId = UUID.randomUUID().toString(),
            sequenceNumber = 1L,
            timestampMs = System.currentTimeMillis(),
            ttlMs = 30_000,
            payload = Json.encodeToString(cfg).toByteArray(Charsets.UTF_8)
        ).signedWith(keys.hmacKey, ctx)

        val frames = BleChunkFraming.split(env.encode(), attMtu = 23, msgId = 9)
        val reassembler = PacketReassembler()
        var decodedBytes: ByteArray? = null
        for (frame in frames) {
            decodedBytes = reassembler.acceptFrame(frame)
        }
        assertNotNull(decodedBytes)
        val decodedEnv = ProtocolEnvelope.decode(decodedBytes!!).getOrThrow()
        assertTrue(decodedEnv.verifyHmac(keys.hmacKey, ctx))
        val decodedCfg = Json.decodeFromString<RecorderConfigPayload>(decodedEnv.payload.toString(Charsets.UTF_8))
        assertEquals(cfg, decodedCfg)
    }
}
