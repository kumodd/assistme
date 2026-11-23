package com.personalai.recorder.audio

import com.personalai.protocol.RecorderConfigPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    private val defaultConfig = RecorderConfigPayload(
        sampleRateHz = 16_000,
        vadThreshold = 0.5f,
        preRollMs = 500,
        silenceTimeoutMs = 1_200,
        maxSegmentDurationMs = 30_000,
        minSpeechDurationMs = 320
    )

    private fun makeFrame(marker: Short, count: Int = 512): ShortArray =
        ShortArray(count) { marker }

    @Test
    fun `continuous silence never emits a segment`() {
        val segmenter = SpeechSegmenter("sess-1", defaultConfig)
        var t = 1_000_000L
        repeat(200) {
            val res = segmenter.onFrame(makeFrame(0), speechProbability = 0.05f, frameStartTimestampMs = t)
            assertNull(res.completedSegment)
            assertEquals(SegmenterPhase.LISTENING, res.phase)
            t += 32L
        }
        assertNull(segmenter.flush())
    }

    @Test
    fun `pre-roll is prepended without duplication and silence timeout seals segment`() {
        val segmenter = SpeechSegmenter("sess-2", defaultConfig)
        var t = 10_000L

        // Push 20 frames (640 ms > 500 ms pre-roll) of quiet ambient frames marked 7.
        repeat(20) {
            val res = segmenter.onFrame(makeFrame(7), 0.1f, t)
            assertNull(res.completedSegment)
            t += 32L
        }

        // Push 15 speech frames (480 ms >= 320 ms minSpeech) marked 42.
        repeat(15) {
            val res = segmenter.onFrame(makeFrame(42), 0.9f, t)
            assertNull(res.completedSegment)
            assertEquals(SegmenterPhase.SPEECH_ACTIVE, res.phase)
            t += 32L
        }

        // Push trailing silence frames up to silenceTimeoutMs (1200ms / 32ms = 37 frames).
        var emitted: CompletedSpeechSegment? = null
        repeat(45) {
            val res = segmenter.onFrame(makeFrame(1), 0.02f, t)
            if (res.completedSegment != null) {
                emitted = res.completedSegment
            }
            t += 32L
        }

        assertNotNull(emitted)
        val seg = emitted!!
        assertEquals(SegmentCloseReason.SILENCE_TIMEOUT, seg.closeReason)
        // First 8000 samples must be pre-roll (marker 7), immediately followed by marker 42 (no duplicate 42 frames).
        assertEquals(7.toShort(), seg.samples[0])
        assertEquals(7.toShort(), seg.samples[7_999])
        assertEquals(42.toShort(), seg.samples[8_000])
        assertEquals(42.toShort(), seg.samples[8_000 + (15 * 512) - 1])
    }

    @Test
    fun `brief noise shorter than minSpeechDuration is discarded`() {
        val segmenter = SpeechSegmenter("sess-3", defaultConfig)
        var t = 50_000L

        // Only 3 speech frames (96 ms < 320 ms minSpeech).
        repeat(3) {
            segmenter.onFrame(makeFrame(99), 0.95f, t)
            t += 32L
        }

        var emitted: CompletedSpeechSegment? = null
        repeat(40) {
            val res = segmenter.onFrame(makeFrame(0), 0.01f, t)
            if (res.completedSegment != null) emitted = res.completedSegment
            t += 32L
        }

        assertNull(emitted)
    }

    @Test
    fun `long continuous speech splits at maxSegmentDuration without dropping frames`() {
        val shortMaxConfig = defaultConfig.copy(maxSegmentDurationMs = 1_600) // 50 frames * 32 ms
        val segmenter = SpeechSegmenter("sess-4", shortMaxConfig)
        var t = 100_000L
        val emitted = mutableListOf<CompletedSpeechSegment>()

        // 120 continuous speech frames (~3.84 seconds).
        repeat(120) { idx ->
            val res = segmenter.onFrame(makeFrame((idx + 1).toShort()), 0.95f, t)
            res.completedSegment?.let(emitted::add)
            t += 32L
        }
        segmenter.flush()?.let(emitted::add)

        assertTrue("Expected at least 2 split segments, got ${emitted.size}", emitted.size >= 2)
        assertEquals(SegmentCloseReason.MAX_DURATION, emitted[0].closeReason)
        val totalSamples = emitted.sumOf { it.samples.size }
        assertEquals(120 * 512, totalSamples)
    }
}
