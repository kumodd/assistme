package com.personalai.recorder.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SegmentCryptoTest {

    private class InMemoryMasterKeyProvider : MasterKeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun getOrCreateSecretKey(): SecretKey = key
        override fun isHardwareBackedKeyReady(): Boolean = true
    }

    @Test
    fun `multi-chunk WAV encryption and decryption round-trip succeeds with matching checksum`() {
        val crypto = SegmentCrypto(InMemoryMasterKeyProvider())
        // 48,000 samples (~3 seconds) -> 96,044 bytes (> 64 KB chunk size, exercises multi-chunk IV derivation).
        val pcm = ShortArray(48_000) { idx -> ((idx * 17) % 30_000).toShort() }
        val wavBytes = WavHeaderWriter.pcm16ShortsToWavBytes(pcm, sampleRateHz = 16_000, channels = 1)
        val expectedHash = WavHeaderWriter.sha256Hex(wavBytes)

        val ctx = SegmentEncryptionContext(segmentId = "seg-1", sessionId = "sess-1", sequenceNumber = 42L)
        val encOut = ByteArrayOutputStream()
        crypto.encrypt(wavBytes, encOut, ctx)

        val decrypted = crypto.decrypt(ByteArrayInputStream(encOut.toByteArray()), ctx)
        assertArrayEquals(wavBytes, decrypted)
        assertEquals(expectedHash, WavHeaderWriter.sha256Hex(decrypted))
    }

    @Test(expected = GeneralSecurityException::class)
    fun `wrong AAD segment context rejects decryption`() {
        val crypto = SegmentCrypto(InMemoryMasterKeyProvider())
        val wavBytes = WavHeaderWriter.pcm16ShortsToWavBytes(ShortArray(1024) { 123 })
        val encOut = ByteArrayOutputStream()
        crypto.encrypt(wavBytes, encOut, SegmentEncryptionContext("seg-1", "sess-1", 1L))

        crypto.decrypt(
            ByteArrayInputStream(encOut.toByteArray()),
            SegmentEncryptionContext("seg-2", "sess-1", 1L)
        )
    }

    @Test(expected = GeneralSecurityException::class)
    fun `corrupted ciphertext byte fails GCM tag check`() {
        val crypto = SegmentCrypto(InMemoryMasterKeyProvider())
        val wavBytes = WavHeaderWriter.pcm16ShortsToWavBytes(ShortArray(1024) { 456 })
        val ctx = SegmentEncryptionContext("seg-3", "sess-1", 2L)
        val encOut = ByteArrayOutputStream()
        crypto.encrypt(wavBytes, encOut, ctx)

        val tampered = encOut.toByteArray()
        tampered[tampered.size - 5] = (tampered[tampered.size - 5].toInt() xor 0xFF).toByte()

        crypto.decrypt(ByteArrayInputStream(tampered), ctx)
    }
}
