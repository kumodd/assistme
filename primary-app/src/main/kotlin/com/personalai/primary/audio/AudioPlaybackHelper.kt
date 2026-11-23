package com.personalai.primary.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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

    fun togglePlayback(segment: SyncedSegmentEntity) {
        if (segment.audioDeleted) return
        if (_playingSegmentId.value == segment.segmentId) {
            stop()
            return
        }
        stop()
        _playingSegmentId.value = segment.segmentId
        _playbackProgress.value = 0f

        playJob = scope.launch {
            try {
                val wavBytes = audioStorage.decryptWavBytes(
                    relativePath = segment.localEncryptedAudioPath,
                    sessionId = segment.sessionId,
                    segmentId = segment.segmentId
                )
                val pcmOffset = 44.coerceAtMost(wavBytes.size)
                val pcmLen = wavBytes.size - pcmOffset
                if (pcmLen <= 0) return@launch

                val minBuf = AudioTrack.getMinBufferSize(
                    16_000,
                    AudioFormat.CHANNEL_OUT_MONO,
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
                            .setSampleRate(16_000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 8_192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                activeTrack = track
                track.play()

                // Stream in responsive 4 KB (~125ms) frames so Stop responds instantaneously
                // and UI receives smooth playback progress updates.
                val chunkBytes = 4_096
                var cursor = pcmOffset
                val end = wavBytes.size
                while (cursor < end && isActive) {
                    val toWrite = minOf(chunkBytes, end - cursor)
                    val written = track.write(wavBytes, cursor, toWrite)
                    if (written <= 0) break
                    cursor += written
                    _playbackProgress.value = ((cursor - pcmOffset).toFloat() / pcmLen.toFloat()).coerceIn(0f, 1f)
                }

                runCatching { track.stop() }
                runCatching { track.release() }
                activeTrack = null
            } catch (_: Throwable) {
                // Ignore playback interruption
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
}
