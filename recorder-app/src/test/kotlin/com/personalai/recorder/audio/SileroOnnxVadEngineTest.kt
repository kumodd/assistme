package com.personalai.recorder.audio

import com.personalai.protocol.RecorderConfigPayload
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SileroOnnxVadEngineTest {

    private fun loadSileroModel(): ByteArray {
        val candidates = listOf(
            File("src/main/assets/models/silero_vad.onnx"),
            File("recorder-app/src/main/assets/models/silero_vad.onnx")
        )
        return candidates.firstOrNull { it.exists() }?.readBytes()
            ?: error("Could not locate silero_vad.onnx")
    }

    @Test
    fun `silero onnx model loads and scores pure silence near zero`() {
        SileroOnnxVadEngine(
            modelBytes = loadSileroModel(),
            sampleRateHz = 16_000,
            frameSizeSamples = 512
        ).use { vad ->
            val silentFrame = ShortArray(512)
            var lastProb = 1f
            repeat(10) {
                lastProb = vad.processFrame(silentFrame)
            }
            assertTrue("Expected silence probability < 0.25, got $lastProb", lastProb < 0.25f)
            vad.reset()
            val afterReset = vad.processFrame(silentFrame)
            assertTrue("Expected valid probability in [0, 1], got $afterReset", afterReset in 0f..1f)
        }
    }

    @Test
    fun `silero onnx detects real speech wav and produces segmented output`() {
        val wavStream = javaClass.classLoader!!.getResourceAsStream("test_speech.wav")
            ?: error("Missing test_speech.wav")
        val wavBytes = wavStream.use { it.readBytes() }
        // Parse standard 44-byte RIFF PCM WAV (skip to 'data' chunk robustly)
        var offset = 12
        var dataOffset = -1
        var dataSize = 0
        val bb = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        while (offset + 8 <= wavBytes.size) {
            val id = String(wavBytes, offset, 4, Charsets.US_ASCII)
            val size = bb.getInt(offset + 4)
            if (id == "data") {
                dataOffset = offset + 8
                dataSize = size
                break
            }
            offset += 8 + size + (size and 1)
        }
        assertTrue("Valid data chunk found", dataOffset > 0 && dataSize > 0)

        val sampleCount = dataSize / 2
        val pcm = ShortArray(sampleCount)
        bb.position(dataOffset)
        for (i in 0 until sampleCount) {
            pcm[i] = bb.short
        }

        val segmenter = SpeechSegmenter(
            sessionId = "speech-test",
            config = RecorderConfigPayload(
                vadThreshold = 0.50f,
                preRollMs = 500,
                silenceTimeoutMs = 1_200,
                maxSegmentDurationMs = 30_000,
                minSpeechDurationMs = 300
            )
        )

        var maxProb = 0f
        var speechFrames = 0
        val emitted = mutableListOf<CompletedSpeechSegment>()

        SileroOnnxVadEngine(loadSileroModel(), 16_000, 512).use { vad ->
            var idx = 0
            var ts = 1_000L
            while (idx + 512 <= pcm.size) {
                val frame = pcm.copyOfRange(idx, idx + 512)
                val prob = vad.processFrame(frame)
                if (prob > maxProb) maxProb = prob
                if (prob >= 0.50f) speechFrames++
                segmenter.onFrame(frame, prob, ts).completedSegment?.let(emitted::add)
                idx += 512
                ts += 32L
            }
            // Append 1.5s trailing silence to seal segment naturally
            val silence = ShortArray(512)
            repeat(50) {
                val prob = vad.processFrame(silence)
                segmenter.onFrame(silence, prob, ts).completedSegment?.let(emitted::add)
                ts += 32L
            }
        }

        assertTrue("Expected Silero maxProb > 0.5 on real speech, got $maxProb", maxProb > 0.5f)
        assertTrue("Expected speechFrames > 5, got $speechFrames", speechFrames > 5)
        assertTrue("Expected at least 1 emitted speech segment, got ${emitted.size}", emitted.isNotEmpty())
    }
}
