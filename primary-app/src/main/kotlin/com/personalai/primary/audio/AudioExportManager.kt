package com.personalai.primary.audio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.personalai.primary.data.db.SyncedSegmentEntity
import com.personalai.primary.security.PrimaryEncryptedAudioStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class ExportedAudioResult(
    val segmentId: String,
    val fileName: String,
    val relativeFolder: String,
    val contentUri: Uri,
    val sizeBytes: Long
)

/**
 * Decrypts a single AES-256-GCM synced audio chunk and exports it as a standard playable WAV
 * into Android's public `Downloads/SecondMind/` folder via Scoped Storage (`MediaStore.Downloads`).
 */
@Singleton
class AudioExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioStorage: PrimaryEncryptedAudioStorage
) {
    suspend fun downloadChunkToDevice(segment: SyncedSegmentEntity): Result<ExportedAudioResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (segment.audioDeleted || !audioStorage.hasEncryptedAudio(segment.localEncryptedAudioPath)) {
                    throw IllegalStateException("Audio chunk #${segment.sequenceNumber} was already pruned to save space (transcript is still saved).")
                }

                val wavBytes = audioStorage.decryptWavBytes(
                    relativePath = segment.localEncryptedAudioPath,
                    sessionId = segment.sessionId,
                    segmentId = segment.segmentId
                )

                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(Date(segment.startTimestampMs))
                val fileName = "SecondMind_seg${segment.sequenceNumber}_${stamp}.wav"
                val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/SecondMind"

                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Unable to create file in Downloads/SecondMind.")

                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(wavBytes)
                        out.flush()
                    } ?: throw IllegalStateException("Unable to open output stream for $fileName.")

                    val doneValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, doneValues, null, null)
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }

                ExportedAudioResult(
                    segmentId = segment.segmentId,
                    fileName = fileName,
                    relativeFolder = "Downloads/SecondMind",
                    contentUri = uri,
                    sizeBytes = wavBytes.size.toLong()
                )
            }
        }

    fun buildShareAudioIntent(exported: ExportedAudioResult): Intent {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, exported.contentUri)
            putExtra(Intent.EXTRA_SUBJECT, exported.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(share, "Share ${exported.fileName}")
    }
}
