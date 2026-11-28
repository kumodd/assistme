package com.personalai.primary.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.personalai.primary.data.db.SyncedSegmentEntity
import com.personalai.primary.security.PrimaryEncryptedAudioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlaybackHelper @Inject constructor(
    private val audioStorage: PrimaryEncryptedAudioStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playJob: Job? = null
    private var activeTrack: AudioTrack? = null

    private val _playingSegmentId = MutableStateFlow<String?>(null)
    val playingSegmentId: StateFlow<String?> = _playingSegmentId.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    fun togglePlayback(segment: SyncedSegmentEntity) {
        if (segment.audioDeleted) {
            _lastError.value = "Audio for #${segment.sequenceNumber} was pruned (transcript is preserved)."
            return
        }
        if (_playingSegmentId.value == segment.segmentId) {
            stop()
            return
        }
        stop()
        _lastError.value = null
        _playingSegmentId.value = segment.segmentId
        _playbackProgress.value = 0f

        playJob = scope.launch {
            try {
                val wavBytes = audioStorage.decryptWavBytes(
                    relativePath = segment.localEncryptedAudioPath,
                    sessionId = segment.sessionId,
                    segmentId = segment.segmentId
                )
                val wavMeta = parseWavHeader(wavBytes)
                val pcmLen = (wavBytes.size - wavMeta.dataOffset).coerceAtLeast(0)
                if (pcmLen <= 0) {
                    _lastError.value = "Audio chunk #${segment.sequenceNumber} contains no PCM audio frames."
                    return@launch
                }

                val channelMask = if (wavMeta.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val minBuf = AudioTrack.getMinBufferSize(
                    wavMeta.sampleRateHz,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(wavMeta.sampleRateHz)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 8_192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                activeTrack = track
                track.play()

                val chunkBytes = 4_096
                var cursor = wavMeta.dataOffset
                val end = wavBytes.size
                var lastProgressEmitMs = 0L
                while (cursor < end && isActive) {
                    val toWrite = minOf(chunkBytes, end - cursor)
                    val written = track.write(wavBytes, cursor, toWrite)
                    if (written <= 0) break
                    cursor += written
                    val now = SystemClock.elapsedRealtime()
                    if (cursor >= end || now - lastProgressEmitMs >= 160L) {
                        lastProgressEmitMs = now
                        _playbackProgress.value = ((cursor - wavMeta.dataOffset).toFloat() / pcmLen.toFloat()).coerceIn(0f, 1f)
                    }
                }

                runCatching { track.stop() }
                runCatching { track.release() }
                activeTrack = null
            } catch (t: Throwable) {
                if (isActive) {
                    Log.w(TAG, "Audio playback failed for seg=${segment.segmentId}: ${t.message}")
                    _lastError.value = "Playback failed for #${segment.sequenceNumber}: ${t.message ?: "unknown error"}"
                }
            } finally {
                if (_playingSegmentId.value == segment.segmentId) {
                    _playingSegmentId.value = null
                    _playbackProgress.value = 0f
                }
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        activeTrack?.let { tr ->
            runCatching { tr.pause() }
            runCatching { tr.flush() }
            runCatching { tr.stop() }
            runCatching { tr.release() }
        }
        activeTrack = null
        _playingSegmentId.value = null
        _playbackProgress.value = 0f
    }

    private data class WavMeta(
        val sampleRateHz: Int,
        val channels: Int,
        val dataOffset: Int
    )

    private fun parseWavHeader(wav: ByteArray): WavMeta {
        var sampleRate = 16_000
        var channels = 1
        var dataOffset = 44.coerceAtMost(wav.size)
        if (wav.size < 44) return WavMeta(sampleRate, channels, 0)
        var pos = 12
        while (pos + 8 <= wav.size) {
            val tag = String(wav, pos, 4, Charsets.US_ASCII)
            val size = readLeInt(wav, pos + 4)
            if (tag == "fmt " && pos + 8 + 16 <= wav.size) {
                channels = ((wav[pos + 10].toInt() and 0xFF) or ((wav[pos + 11].toInt() and 0xFF) shl 8)).coerceIn(1, 2)
                sampleRate = readLeInt(wav, pos + 12).coerceIn(8_000, 48_000)
            } else if (tag == "data") {
                dataOffset = pos + 8
                break
            }
            if (size < 0) break
            pos += 8 + size + (size and 1)
        }
        return WavMeta(sampleRate, channels, dataOffset.coerceAtMost(wav.size))
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    companion object {
        private const val TAG = "AudioPlaybackHelper"
    }
}
