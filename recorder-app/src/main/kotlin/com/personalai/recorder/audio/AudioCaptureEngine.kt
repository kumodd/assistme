package com.personalai.recorder.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.personalai.protocol.AudioSourcePreset
import com.personalai.protocol.RecorderConfigPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

data class LiveAudioTelemetry(
    val audioLevelDb: Float = -90f,
    val vadProbability: Float = 0f,
    val isClientSilenced: Boolean = false,
    val inputDeviceName: String = "Default Mic",
    val bufferOverrunCount: Long = 0L
)

sealed interface CaptureEvent {
    data class Frame(
        val samples: ShortArray,
        val vadProbability: Float,
        val audioLevelDb: Float,
        val timestampMs: Long
    ) : CaptureEvent

    data class SilencedChanged(val isSilenced: Boolean, val sourceDesc: String) : CaptureEvent
    data class InputDeviceChanged(val deviceName: String) : CaptureEvent
    data class CaptureError(val code: Int, val message: String) : CaptureEvent
    data object OverrunDetected : CaptureEvent
}

@Singleton
class AudioCaptureEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _telemetry = MutableStateFlow(LiveAudioTelemetry())
    val telemetry: StateFlow<LiveAudioTelemetry> = _telemetry.asStateFlow()

    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<CaptureEvent> = _events.asSharedFlow()

    private val running = AtomicBoolean(false)
    private val resourceLock = Any()
    private val captureGeneration = AtomicLong(0L)
    private var activeGeneration = 0L
    private var captureJob: Job? = null
    private var vadEngine: VadEngine? = null
    private var activeCallback: AudioManager.AudioRecordingCallback? = null
    private var activeAudioRecord: AudioRecord? = null

    private fun createVadEngine(sampleRateHz: Int): VadEngine {
        return try {
            val modelBytes = context.assets.open("models/silero_vad.onnx").use { it.readBytes() }
            SileroOnnxVadEngine(
                modelBytes = modelBytes,
                sampleRateHz = sampleRateHz,
                frameSizeSamples = if (sampleRateHz == 16_000) 512 else 256
            )
        } catch (_: Throwable) {
            EnergyFallbackVadEngine(
                sampleRateHz = sampleRateHz,
                frameSizeSamples = if (sampleRateHz == 16_000) 512 else 256
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startCapture(config: RecorderConfigPayload): Result<Unit> {
        if (running.get()) {
            stopCapture()
        }
        val myGeneration = captureGeneration.incrementAndGet()
        running.set(true)

        val sampleRateHz = config.sampleRateHz
        val frameSizeSamples = if (sampleRateHz == 16_000) 512 else 256
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes == AudioRecord.ERROR || minBufBytes == AudioRecord.ERROR_BAD_VALUE) {
            running.set(false)
            return Result.failure(IllegalStateException("Invalid AudioRecord minBufferSize: $minBufBytes"))
        }

        val frameBytes = frameSizeSamples * 2
        val bufferBytes = maxOf(minBufBytes * 2, frameBytes * 16)
        val audioSource = when (config.audioSource) {
            AudioSourcePreset.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            AudioSourcePreset.MIC -> MediaRecorder.AudioSource.MIC
            AudioSourcePreset.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        }

        val audioRecord = try {
            AudioRecord(
                audioSource,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (t: Throwable) {
            running.set(false)
            return Result.failure(t)
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            running.set(false)
            return Result.failure(IllegalStateException("AudioRecord failed to initialize (state=${audioRecord.state})"))
        }

        val engine = createVadEngine(sampleRateHz)
        val targetSessionId = audioRecord.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            private var lastSilencedState: Boolean? = null
            private var lastDeviceId: Int? = null

            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == targetSessionId } ?: return
                val silenced = mine.isClientSilenced
                val device = mine.audioDevice
                val deviceLabel = device?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Built-in Mic"

                if (device != null && device.id != lastDeviceId) {
                    lastDeviceId = device.id
                    _telemetry.value = _telemetry.value.copy(inputDeviceName = deviceLabel)
                    _events.tryEmit(CaptureEvent.InputDeviceChanged(deviceLabel))
                }

                if (lastSilencedState != silenced) {
                    lastSilencedState = silenced
                    _telemetry.value = _telemetry.value.copy(isClientSilenced = silenced)
                    _events.tryEmit(
                        CaptureEvent.SilencedChanged(
                            isSilenced = silenced,
                            sourceDesc = "audioSource=${mine.clientAudioSource}"
                        )
                    )
                }
            }
        }

        synchronized(resourceLock) {
            cleanupCaptureResourcesLocked()
            activeGeneration = myGeneration
            vadEngine = engine
            activeAudioRecord = audioRecord
            activeCallback = callback
        }
        audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))

        captureJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frameBuffer = ShortArray(frameSizeSamples)
            val expectedFrameMs = (frameSizeSamples * 1000L) / sampleRateHz
            var overrunCount = 0L
            var lastReadElapsedMs = SystemClock.elapsedRealtime()

            try {
                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    _events.tryEmit(
                        CaptureEvent.CaptureError(
                            code = -10,
                            message = "AudioRecord failed to enter RECORDSTATE_RECORDING"
                        )
                    )
                    return@launch
                }

                while (isActive && running.get() && captureGeneration.get() == myGeneration) {
                    val readCount = audioRecord.read(frameBuffer, 0, frameSizeSamples, AudioRecord.READ_BLOCKING)
                    val nowWallMs = System.currentTimeMillis()
                    val nowElapsedMs = SystemClock.elapsedRealtime()

                    if (readCount < 0) {
                        _events.tryEmit(
                            CaptureEvent.CaptureError(
                                code = readCount,
                                message = "AudioRecord.read error code=$readCount"
                            )
                        )
                        break
                    }
                    if (readCount == 0) continue

                    val deltaMs = nowElapsedMs - lastReadElapsedMs
                    lastReadElapsedMs = nowElapsedMs
                    if (deltaMs > expectedFrameMs * 4) {
                        overrunCount++
                        _events.tryEmit(CaptureEvent.OverrunDetected)
                    }

                    if (readCount < frameSizeSamples) {
                        continue
                    }

                    val isSilenced = _telemetry.value.isClientSilenced
                    val db = computeRmsDb(frameBuffer)
                    val prob = if (isSilenced || !running.get()) {
                        0f
                    } else {
                        runCatching { engine.processFrame(frameBuffer) }.getOrDefault(0f)
                    }

                    _telemetry.value = _telemetry.value.copy(
                        audioLevelDb = db,
                        vadProbability = prob,
                        bufferOverrunCount = overrunCount
                    )

                    if (!isSilenced) {
                        _events.tryEmit(
                            CaptureEvent.Frame(
                                samples = frameBuffer.copyOf(),
                                vadProbability = prob,
                                audioLevelDb = db,
                                timestampMs = nowWallMs - expectedFrameMs
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    _events.tryEmit(
                        CaptureEvent.CaptureError(
                            code = -99,
                            message = t.message ?: "Unexpected audio capture failure"
                        )
                    )
                }
            } finally {
                cleanupIfGenerationMatches(myGeneration)
            }
        }

        return Result.success(Unit)
    }

    fun stopCapture() {
        running.set(false)
        captureGeneration.incrementAndGet()
        synchronized(resourceLock) {
            activeAudioRecord?.let { record ->
                runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                }
            }
        }
        captureJob?.cancel()
        captureJob = null
        synchronized(resourceLock) {
            cleanupCaptureResourcesLocked()
        }
        _telemetry.value = LiveAudioTelemetry()
    }

    private fun cleanupIfGenerationMatches(expectedGeneration: Long) = synchronized(resourceLock) {
        if (activeGeneration == expectedGeneration) {
            cleanupCaptureResourcesLocked()
        }
    }

    private fun cleanupCaptureResourcesLocked() {
        activeCallback?.let { cb ->
            runCatching { audioManager.unregisterAudioRecordingCallback(cb) }
        }
        activeCallback = null

        activeAudioRecord?.let { record ->
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            runCatching { record.release() }
        }
        activeAudioRecord = null

        vadEngine?.let { engine -> runCatching { engine.close() } }
        vadEngine = null
    }

    private fun computeRmsDb(samples: ShortArray): Float {
        if (samples.isEmpty()) return -90f
        var sumSq = 0.0
        for (sample in samples) {
            val norm = sample / 32768.0
            sumSq += norm * norm
        }
        val rms = sqrt(sumSq / samples.size)
        if (rms <= 1e-6) return -90f
        return (20.0 * log10(rms)).toFloat().coerceIn(-90f, 0f)
    }
}

class EnergyFallbackVadEngine(
    override val sampleRateHz: Int = 16_000,
    override val frameSizeSamples: Int = 512
) : VadEngine {
    override fun reset() = Unit

    override fun processFrame(pcm16: ShortArray): Float {
        var sumSq = 0.0
        for (sample in pcm16) {
            val norm = sample / 32768.0
            sumSq += norm * norm
        }
        val rms = sqrt(sumSq / pcm16.size.coerceAtLeast(1))
        return (rms * 15.0).toFloat().coerceIn(0f, 1f)
    }

    override fun close() = Unit
}
