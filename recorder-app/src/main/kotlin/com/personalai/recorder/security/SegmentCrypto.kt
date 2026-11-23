package com.personalai.recorder.security

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SegmentEncryptionContext(
    val segmentId: String,
    val sessionId: String,
    val sequenceNumber: Long
)

/**
 * Envelope encryption for speech segments:
 * 1. Generates a random 256-bit per-segment DEK and wraps it using the Android Keystore master KEK
 *    with Keystore-generated randomized IV (maximum compatibility across all Keymaster/StrongBox HALs).
 * 2. Encrypts PCM/WAV payload in 64 KB AES-256-GCM chunks using the per-segment DEK and non-repeating
 *    chunk IVs with AAD binding segmentId, sessionId, sequenceNumber, and chunkIndex.
 */
class SegmentCrypto(
    private val keyProvider: MasterKeyProvider,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    companion object {
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte(), 'E'.code.toByte())
        private const val FORMAT_VERSION: Byte = 2
        private const val DEK_SIZE_BYTES = 32
        private const val BASE_IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
        const val DEFAULT_CHUNK_SIZE = 65_536
        private const val ALGORITHM = "AES/GCM/NoPadding"
    }

    fun encrypt(
        plaintext: ByteArray,
        output: OutputStream,
        context: SegmentEncryptionContext,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ) {
        val masterKey = keyProvider.getOrCreateSecretKey()
        val rawDek = ByteArray(DEK_SIZE_BYTES).also(secureRandom::nextBytes)
        val dekSpec = SecretKeySpec(rawDek, "AES")

        val wrapCipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, masterKey)
            updateAAD(buildDekAad(context))
        }
        val wrappedIv = wrapCipher.iv
        val wrappedDek = wrapCipher.doFinal(rawDek)
        rawDek.fill(0)

        val baseIv = ByteArray(BASE_IV_SIZE).also(secureRandom::nextBytes)
        val dataOut = DataOutputStream(output)

        dataOut.write(MAGIC)
        dataOut.writeByte(FORMAT_VERSION.toInt())
        dataOut.writeInt(wrappedIv.size)
        dataOut.write(wrappedIv)
        dataOut.writeInt(wrappedDek.size)
        dataOut.write(wrappedDek)
        dataOut.write(baseIv)
        dataOut.writeInt(chunkSize)
        dataOut.writeLong(plaintext.size.toLong())

        var offset = 0
        var chunkIndex = 0L
        while (offset < plaintext.size || (plaintext.isEmpty() && chunkIndex == 0L)) {
            val len = minOf(chunkSize, plaintext.size - offset)
            val chunkIv = deriveChunkIv(baseIv, chunkIndex)
            val cipher = Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.ENCRYPT_MODE, dekSpec, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
                updateAAD(buildChunkAad(context, chunkIndex))
            }
            val encryptedChunk = cipher.doFinal(plaintext, offset, len)
            dataOut.writeInt(encryptedChunk.size)
            dataOut.write(encryptedChunk)
            offset += len
            chunkIndex++
            if (plaintext.isEmpty()) break
        }
        dataOut.flush()
    }

    fun decrypt(input: InputStream, context: SegmentEncryptionContext): ByteArray {
        val masterKey = keyProvider.getOrCreateSecretKey()
        val dataIn = DataInputStream(input)

        val magic = ByteArray(4)
        dataIn.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw GeneralSecurityException("Invalid encrypted segment magic header")
        }
        val version = dataIn.readByte()
        if (version != FORMAT_VERSION) {
            throw GeneralSecurityException("Unsupported encrypted segment version: $version")
        }

        val wrappedIvLen = dataIn.readInt()
        if (wrappedIvLen !in 12..32) {
            throw GeneralSecurityException("Invalid wrapped IV length: $wrappedIvLen")
        }
        val wrappedIv = ByteArray(wrappedIvLen)
        dataIn.readFully(wrappedIv)

        val wrappedDekLen = dataIn.readInt()
        if (wrappedDekLen !in 32..128) {
            throw GeneralSecurityException("Invalid wrapped DEK length: $wrappedDekLen")
        }
        val wrappedDek = ByteArray(wrappedDekLen)
        dataIn.readFully(wrappedDek)

        val unwrapCipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, wrappedIv))
            updateAAD(buildDekAad(context))
        }
        val rawDek = unwrapCipher.doFinal(wrappedDek)
        val dekSpec = SecretKeySpec(rawDek, "AES")
        rawDek.fill(0)

        val baseIv = ByteArray(BASE_IV_SIZE)
        dataIn.readFully(baseIv)
        val chunkSize = dataIn.readInt()
        val expectedPlaintextLen = dataIn.readLong()
        if (chunkSize <= 0 || expectedPlaintextLen < 0) {
            throw GeneralSecurityException("Corrupt encrypted segment header metadata")
        }

        val out = ByteArrayOutputStream(expectedPlaintextLen.toInt().coerceAtLeast(0))
        var chunkIndex = 0L
        var totalDecoded = 0L

        while (totalDecoded < expectedPlaintextLen || (expectedPlaintextLen == 0L && chunkIndex == 0L)) {
            val encryptedChunkLen = dataIn.readInt()
            if (encryptedChunkLen <= 0 || encryptedChunkLen > chunkSize + 64) {
                throw GeneralSecurityException("Invalid encrypted chunk size: $encryptedChunkLen")
            }
            val encryptedChunk = ByteArray(encryptedChunkLen)
            dataIn.readFully(encryptedChunk)

            val chunkIv = deriveChunkIv(baseIv, chunkIndex)
            val cipher = Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.DECRYPT_MODE, dekSpec, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
                updateAAD(buildChunkAad(context, chunkIndex))
            }
            val plainChunk = cipher.doFinal(encryptedChunk)
            out.write(plainChunk)
            totalDecoded += plainChunk.size
            chunkIndex++
            if (expectedPlaintextLen == 0L) break
        }

        if (totalDecoded != expectedPlaintextLen) {
            throw GeneralSecurityException(
                "Decrypted byte length mismatch: expected=$expectedPlaintextLen actual=$totalDecoded"
            )
        }
        return out.toByteArray()
    }

    private fun deriveChunkIv(baseIv: ByteArray, chunkIndex: Long): ByteArray {
        val iv = baseIv.copyOf()
        val indexBytes = ByteBuffer.allocate(8).putLong(chunkIndex).array()
        for (i in 0 until 8) {
            iv[4 + i] = (iv[4 + i].toInt() xor indexBytes[i].toInt()).toByte()
        }
        return iv
    }

    private fun buildDekAad(context: SegmentEncryptionContext): ByteArray {
        return "${context.segmentId}|${context.sessionId}|${context.sequenceNumber}|DEK"
            .toByteArray(Charsets.UTF_8)
    }

    private fun buildChunkAad(context: SegmentEncryptionContext, chunkIndex: Long): ByteArray {
        return "${context.segmentId}|${context.sessionId}|${context.sequenceNumber}|$chunkIndex"
            .toByteArray(Charsets.UTF_8)
    }
}
