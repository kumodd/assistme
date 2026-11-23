package com.personalai.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated, AES-256-GCM encrypted streaming protocol over BLE L2CAP CoC sockets.
 * Supports resumable byte offsets and per-chunk integrity verification.
 */
object L2capSyncFraming {
    const val STATUS_OK: Byte = 0
    const val STATUS_NOT_FOUND: Byte = 1
    const val STATUS_CORRUPT: Byte = 2
    const val STREAM_CHUNK_SIZE: Int = 16_384
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val ALGORITHM = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    data class SegmentPullRequest(
        val segmentId: String,
        val resumeOffsetBytes: Long
    )

    fun writePullRequest(out: OutputStream, segmentId: String, resumeOffsetBytes: Long) {
        val dos = DataOutputStream(out)
        val idBytes = segmentId.toByteArray(Charsets.UTF_8)
        dos.writeShort(idBytes.size)
        dos.write(idBytes)
        dos.writeLong(resumeOffsetBytes.coerceAtLeast(0L))
        dos.flush()
    }

    fun writeEndOfSession(out: OutputStream) {
        val dos = DataOutputStream(out)
        dos.writeShort(0)
        dos.flush()
    }

    fun readPullRequest(input: InputStream): SegmentPullRequest? {
        val dis = DataInputStream(input)
        val len = dis.readUnsignedShort()
        if (len == 0) return null
        require(len <= 256) { "Invalid segmentId length: $len" }
        val idBytes = ByteArray(len)
        dis.readFully(idBytes)
        val offset = dis.readLong().coerceAtLeast(0L)
        return SegmentPullRequest(String(idBytes, Charsets.UTF_8), offset)
    }

    fun writeErrorStatus(out: OutputStream, status: Byte) {
        val dos = DataOutputStream(out)
        dos.writeByte(status.toInt())
        dos.writeLong(0L)
        dos.flush()
    }

    fun streamEncryptedSegment(
        out: OutputStream,
        segmentId: String,
        wavBytes: ByteArray,
        resumeOffsetBytes: Long,
        sessionEncryptionKey: ByteArray
    ) {
        val dos = DataOutputStream(out)
        val keySpec = SecretKeySpec(sessionEncryptionKey, "AES")
        val totalLen = wavBytes.size.toLong()
        dos.writeByte(STATUS_OK.toInt())
        dos.writeLong(totalLen)

        var offset = resumeOffsetBytes.toInt().coerceIn(0, wavBytes.size)
        while (offset < wavBytes.size) {
            val plainLen = minOf(STREAM_CHUNK_SIZE, wavBytes.size - offset)
            val iv = ByteArray(IV_SIZE).also(secureRandom::nextBytes)
            val cipher = Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD("$segmentId|$offset".toByteArray(Charsets.UTF_8))
            }
            val encrypted = cipher.doFinal(wavBytes, offset, plainLen)
            dos.writeInt(plainLen)
            dos.write(iv)
            dos.writeInt(encrypted.size)
            dos.write(encrypted)
            offset += plainLen
        }
        dos.writeInt(0) // EOF marker
        dos.flush()
    }

    /**
     * Reads an encrypted segment stream starting from [resumeOffsetBytes] and delivers plaintext
     * chunks to [onChunk]. Returns total expected WAV byte length on success, or throws on
     * protocol/authentication/status errors.
     */
    fun receiveEncryptedSegment(
        input: InputStream,
        segmentId: String,
        resumeOffsetBytes: Long,
        sessionEncryptionKey: ByteArray,
        onChunk: (bytes: ByteArray, newOffset: Long, totalBytes: Long) -> Unit
    ): Long {
        val dis = DataInputStream(input)
        val status = dis.readByte()
        val totalLen = dis.readLong()
        if (status != STATUS_OK) {
            throw IllegalStateException("Peer returned sync error status=$status for $segmentId")
        }

        val keySpec = SecretKeySpec(sessionEncryptionKey, "AES")
        var currentOffset = resumeOffsetBytes
        while (true) {
            val plainLen = dis.readInt()
            if (plainLen == 0) break
            if (plainLen < 0 || plainLen > STREAM_CHUNK_SIZE * 2) {
                throw GeneralSecurityException("Invalid L2CAP chunk plainLen=$plainLen")
            }
            val iv = ByteArray(IV_SIZE)
            dis.readFully(iv)
            val cipherLen = dis.readInt()
            if (cipherLen <= 0 || cipherLen > plainLen + 64) {
                throw GeneralSecurityException("Invalid L2CAP chunk cipherLen=$cipherLen")
            }
            val encrypted = ByteArray(cipherLen)
            dis.readFully(encrypted)

            val cipher = Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD("$segmentId|$currentOffset".toByteArray(Charsets.UTF_8))
            }
            val plain = cipher.doFinal(encrypted)
            currentOffset += plain.size
            onChunk(plain, currentOffset, totalLen)
        }
        return totalLen
    }
}
