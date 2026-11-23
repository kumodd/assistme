package com.personalai.recorder.security

import android.content.Context
import android.os.StatFs
import com.personalai.recorder.data.db.AudioSegmentEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class StoredEncryptedFile(
    val relativePath: String,
    val plaintextSize: Long,
    val encryptedSize: Long,
    val sha256Checksum: String
)

data class StorageStats(
    val totalBytes: Long,
    val availableBytes: Long
)

@Singleton
class EncryptedSegmentStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val segmentCrypto: SegmentCrypto
) {
    private val baseDir: File
        get() = File(context.filesDir, "audio_segments").apply { mkdirs() }

    private val quarantineDir: File
        get() = File(context.filesDir, "quarantine_segments").apply { mkdirs() }

    fun getStorageStats(): StorageStats {
        val statFs = StatFs(context.filesDir.absolutePath)
        return StorageStats(
            totalBytes = statFs.totalBytes,
            availableBytes = statFs.availableBytes
        )
    }

    fun hasMinimumFreeSpace(minFreeMb: Long): Boolean {
        val requiredBytes = minFreeMb * 1024L * 1024L
        return getStorageStats().availableBytes >= requiredBytes
    }

    fun writeEncryptedWav(
        segmentId: String,
        sessionId: String,
        sequenceNumber: Long,
        pcmSamples: ShortArray,
        sampleRateHz: Int,
        channels: Int
    ): StoredEncryptedFile {
        val wavBytes = WavHeaderWriter.pcm16ShortsToWavBytes(
            samples = pcmSamples,
            sampleRateHz = sampleRateHz,
            channels = channels
        )
        val checksum = WavHeaderWriter.sha256Hex(wavBytes)

        val sessionDir = File(baseDir, sessionId).apply { mkdirs() }
        val finalFile = File(sessionDir, "$segmentId.enc")
        val tempFile = File(sessionDir, "$segmentId.enc.tmp")

        val encContext = SegmentEncryptionContext(
            segmentId = segmentId,
            sessionId = sessionId,
            sequenceNumber = sequenceNumber
        )

        try {
            FileOutputStream(tempFile).use { fos ->
                val bos = BufferedOutputStream(fos)
                segmentCrypto.encrypt(wavBytes, bos, encContext)
                bos.flush()
                runCatching { fos.fd.sync() }
            }

            if (finalFile.exists() && !finalFile.delete()) {
                throw IOException("Could not replace existing file ${finalFile.name}")
            }
            if (!tempFile.renameTo(finalFile)) {
                throw IOException("Failed atomic rename from ${tempFile.name} to ${finalFile.name}")
            }
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }

        val relativePath = "$sessionId/${finalFile.name}"
        return StoredEncryptedFile(
            relativePath = relativePath,
            plaintextSize = wavBytes.size.toLong(),
            encryptedSize = finalFile.length(),
            sha256Checksum = checksum
        )
    }

    fun decryptAndVerify(segment: AudioSegmentEntity): ByteArray {
        val target = File(baseDir, segment.filePath)
        if (!target.exists()) {
            throw IOException("Encrypted segment missing on disk: ${segment.filePath}")
        }
        val encContext = SegmentEncryptionContext(
            segmentId = segment.segmentId,
            sessionId = segment.sessionId,
            sequenceNumber = segment.sequenceNumber
        )
        val wavBytes = BufferedInputStream(FileInputStream(target)).use { bis ->
            segmentCrypto.decrypt(bis, encContext)
        }
        val actualChecksum = WavHeaderWriter.sha256Hex(wavBytes)
        if (!actualChecksum.equals(segment.checksum, ignoreCase = true)) {
            throw SecurityException(
                "Checksum mismatch for ${segment.segmentId}: expected=${segment.checksum} actual=$actualChecksum"
            )
        }
        return wavBytes
    }

    fun quarantineFile(relativePath: String) {
        val src = File(baseDir, relativePath)
        if (!src.exists()) return
        val dst = File(quarantineDir, "${System.currentTimeMillis()}_${src.name}")
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    fun deleteSegmentFile(relativePath: String): Boolean {
        val file = File(baseDir, relativePath)
        return !file.exists() || file.delete()
    }

    fun wipeAllRecordings() {
        baseDir.deleteRecursively()
        quarantineDir.deleteRecursively()
        baseDir.mkdirs()
    }
}
