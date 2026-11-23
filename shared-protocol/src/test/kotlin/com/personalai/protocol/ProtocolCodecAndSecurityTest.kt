package com.personalai.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

class ProtocolCodecAndSecurityTest {

    @Test
    fun `ecdh pairing produces identical master key, sas code, and session keys on both sides`() {
        val alice = ProtocolCrypto.generateEcKeyPair()
        val bob = ProtocolCrypto.generateEcKeyPair()

        val secretA = ProtocolCrypto.computeEcdhSharedSecret(
            alice.private,
            ProtocolCrypto.decodePublicKeyBase64(ProtocolCrypto.encodePublicKeyBase64(bob.public))
        )
        val secretB = ProtocolCrypto.computeEcdhSharedSecret(
            bob.private,
            ProtocolCrypto.decodePublicKeyBase64(ProtocolCrypto.encodePublicKeyBase64(alice.public))
        )
        assertArrayEquals(secretA, secretB)

        val masterA = ProtocolCrypto.deriveMasterKey(secretA, "dev-a", "dev-b")
        val masterB = ProtocolCrypto.deriveMasterKey(secretB, "dev-a", "dev-b")
        assertArrayEquals(masterA, masterB)

        val sasA = ProtocolCrypto.computeSixDigitSas(secretA, alice.public.encoded, bob.public.encoded, "dev-b")
        val sasB = ProtocolCrypto.computeSixDigitSas(secretB, alice.public.encoded, bob.public.encoded, "dev-b")
        assertEquals(6, sasA.length)
        assertEquals(sasA, sasB)

        val serverNonce = ByteArray(16).also(SecureRandom()::nextBytes)
        val clientNonce = ByteArray(16).also(SecureRandom()::nextBytes)
        val keysA = ProtocolCrypto.deriveSessionKeys(masterA, serverNonce, clientNonce)
        val keysB = ProtocolCrypto.deriveSessionKeys(masterB, serverNonce, clientNonce)
        assertArrayEquals(keysA.hmacKey, keysB.hmacKey)
        assertArrayEquals(keysA.encryptionKey, keysB.encryptionKey)
    }

    @Test
    fun `binary envelope round-trip, hmac validation, and tamper rejection`() {
        val hmacKey = ByteArray(32) { idx -> (idx + 1).toByte() }
        val context = "session-ctx".toByteArray()
        val payload = """{"mode":"STOP_SERVICE"}""".toByteArray()

        val original = ProtocolEnvelope(
            msgType = BleMessageType.COMMAND,
            opCode = CommandType.STOP_RECORDING.opCode,
            commandId = UUID.randomUUID().toString(),
            sequenceNumber = 7L,
            timestampMs = 1_700_000_000_000L,
            ttlMs = 30_000,
            payload = payload
        ).signedWith(hmacKey, context)

        val decoded = ProtocolEnvelope.decode(original.encode()).getOrThrow()
        assertEquals(original, decoded)
        assertTrue(decoded.verifyHmac(hmacKey, context))

        val tampered = decoded.copy(payload = """{"mode":"STANDBY_PAUSE"}""".toByteArray())
        assertFalse(tampered.verifyHmac(hmacKey, context))
    }

    @Test
    fun `chunk framing splits large payload and reassembles out-of-order frames`() {
        val bigPayload = ByteArray(1_200) { idx -> (idx % 251).toByte() }
        val frames = BleChunkFraming.split(bigPayload, attMtu = 185, msgId = 42)
        assertTrue(frames.size > 5)

        val reassembler = PacketReassembler()
        val shuffled = frames.reversed()
        var reassembled: ByteArray? = null
        for (frame in shuffled) {
            val out = reassembler.acceptFrame(frame, nowMs = 1_000L)
            if (out != null) reassembled = out
        }
        assertNotNull(reassembled)
        assertArrayEquals(bigPayload, reassembled)
        assertNull(reassembler.acceptFrame(byteArrayOf(1, 2), nowMs = 1_000L))

        // Regression test: Android 14+ negotiates ATT MTU 517, but GATT max attr value is 512B.
        val mtu517Frames = BleChunkFraming.split(bigPayload, attMtu = 517, msgId = 99)
        assertTrue(mtu517Frames.all { it.size <= BleChunkFraming.MAX_GATT_VALUE_BYTES && it.size <= 512 })
    }

    @Test
    fun `replay guard enforces idempotency, sequence monotonicity, and ttl expiration`() = runBlocking {
        val store = InMemoryCommandDedupeStore()
        val guard = CommandReplayGuard(store, clockSkewGraceMs = 1_000L)
        val hmacKey = ByteArray(32) { 9 }
        val ctx = "ctx".toByteArray()
        val now = 1_700_000_100_000L

        val cmd1 = ProtocolEnvelope(
            msgType = BleMessageType.COMMAND,
            opCode = CommandType.PING.opCode,
            commandId = UUID.randomUUID().toString(),
            sequenceNumber = 1L,
            timestampMs = now,
            ttlMs = 15_000,
            payload = "{}".toByteArray()
        ).signedWith(hmacKey, ctx)

        val res1 = guard.validateIncoming(cmd1, hmacKey, ctx, nowMs = now, allowPreAuthCommands = true)
        assertTrue(res1 is CommandValidationResult.Execute)
        guard.recordOutcome(
            cmd1,
            AcknowledgementPayload(
                commandId = cmd1.commandId,
                commandType = CommandType.PING,
                success = true,
                serverTimeMs = now
            ),
            nowMs = now
        )

        // Duplicate commandId returns cached ACK idempotently.
        val dupRes = guard.validateIncoming(cmd1, hmacKey, ctx, nowMs = now + 500L, allowPreAuthCommands = true)
        assertTrue(dupRes is CommandValidationResult.DuplicateCached)

        // New commandId with stale sequenceNumber is rejected as replay.
        val replayCmd = cmd1.copy(commandId = UUID.randomUUID().toString(), sequenceNumber = 1L)
            .signedWith(hmacKey, ctx)
        val replayRes = guard.validateIncoming(replayCmd, hmacKey, ctx, nowMs = now + 500L, allowPreAuthCommands = true)
        assertEquals(
            ProtocolErrorCode.REPLAY_DETECTED,
            (replayRes as CommandValidationResult.Rejected).errorCode
        )

        // Expired command beyond TTL + grace is rejected.
        val expiredCmd = cmd1.copy(
            commandId = UUID.randomUUID().toString(),
            sequenceNumber = 2L,
            timestampMs = now - 40_000L
        ).signedWith(hmacKey, ctx)
        val expiredRes = guard.validateIncoming(expiredCmd, hmacKey, ctx, nowMs = now, allowPreAuthCommands = true)
        assertEquals(
            ProtocolErrorCode.EXPIRED,
            (expiredRes as CommandValidationResult.Rejected).errorCode
        )
    }

    @Test
    fun `legacy ble advertising and scan response payloads fit strictly within 31 bytes`() {
        val tag = ProtocolCrypto.deviceTag8Bytes("device-b-test-id")
        assertEquals(8, tag.size)
        val advBytes = 3 /* flags */ + (2 + 16) /* 128-bit service UUID */
        val minScanRespBytes = 2 + 2 + tag.size /* manufacturer data AD */
        val maxScanRespWithName = minScanRespBytes + 2 + BleProtocolConstants.MAX_SAFE_ADV_NAME_UTF8_BYTES
        assertTrue(advBytes <= BleProtocolConstants.MAX_LEGACY_BLE_ADV_BYTES)
        assertTrue(minScanRespBytes <= BleProtocolConstants.MAX_LEGACY_BLE_ADV_BYTES)
        assertTrue(maxScanRespWithName <= BleProtocolConstants.MAX_LEGACY_BLE_ADV_BYTES)
    }

    @Test
    fun `l2cap encrypted sync stream supports full transfer and byte-offset resume`() {
        val encKey = ByteArray(32) { idx -> (idx * 3 + 7).toByte() }
        val wavPayload = ByteArray(45_000) { idx -> (idx % 251).toByte() }

        // Simulate partial transfer of first 16384 bytes followed by resume from offset 16384.
        val pipeOut = java.io.ByteArrayOutputStream()
        L2capSyncFraming.streamEncryptedSegment(
            out = pipeOut,
            segmentId = "seg-resume-1",
            wavBytes = wavPayload,
            resumeOffsetBytes = 16_384L,
            sessionEncryptionKey = encKey
        )

        val rebuilt = java.io.ByteArrayOutputStream()
        rebuilt.write(wavPayload, 0, 16_384)
        val total = L2capSyncFraming.receiveEncryptedSegment(
            input = java.io.ByteArrayInputStream(pipeOut.toByteArray()),
            segmentId = "seg-resume-1",
            resumeOffsetBytes = 16_384L,
            sessionEncryptionKey = encKey
        ) { chunk, _, _ ->
            rebuilt.write(chunk)
        }
        assertEquals(wavPayload.size.toLong(), total)
        assertArrayEquals(wavPayload, rebuilt.toByteArray())
    }
}
