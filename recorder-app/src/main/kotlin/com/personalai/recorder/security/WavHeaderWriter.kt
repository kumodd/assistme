package com.personalai.recorder.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object WavHeaderWriter {
    const val HEADER_SIZE_BYTES = 44

    fun pcm16ShortsToWavBytes(
        samples: ShortArray,
        sampleRateHz: Int = 16_000,
        channels: Int = 1
    ): ByteArray {
        val pcmByteCount = samples.size * 2
        val totalSize = HEADER_SIZE_BYTES + pcmByteCount
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        writeHeader(buffer, pcmByteCount, sampleRateHz, channels, bitsPerSample = 16)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    fun writeHeader(
        target: ByteBuffer,
        pcmDataByteCount: Int,
        sampleRateHz: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRateHz * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)

        target.order(ByteOrder.LITTLE_ENDIAN)
        target.put("RIFF".toByteArray(Charsets.US_ASCII))
        target.putInt(36 + pcmDataByteCount)
        target.put("WAVE".toByteArray(Charsets.US_ASCII))
        target.put("fmt ".toByteArray(Charsets.US_ASCII))
        target.putInt(16) // PCM subchunk size
        target.putShort(1.toShort()) // AudioFormat = 1 (PCM)
        target.putShort(channels.toShort())
        target.putInt(sampleRateHz)
        target.putInt(byteRate)
        target.putShort(blockAlign.toShort())
        target.putShort(bitsPerSample.toShort())
        target.put("data".toByteArray(Charsets.US_ASCII))
        target.putInt(pcmDataByteCount)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
