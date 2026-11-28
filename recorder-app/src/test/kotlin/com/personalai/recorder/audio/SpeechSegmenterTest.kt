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
        silenceTimeoutMs = 1_600,
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
    fun `pre-roll is prepended without duplication and adaptive silence timeout seals segment`() {
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
            t += 32L
        }

        // Push trailing silence frames up to adaptive short-clip silence timeout (2200ms / 32ms = 68 frames).
        var emitted: CompletedSpeechSegment? = null
        repeat(75) {
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
    fun `adaptive silence patience keeps brief thinking pauses inside one coherent segment`() {
        val segmenter = SpeechSegmenter("sess-adaptive", defaultConfig)
        var t = 20_000L

        // Speak for 1.6s (50 frames)
        repeat(50) {
            val res = segmenter.onFrame(makeFrame(10), 0.85f, t)
            assertNull(res.completedSegment)
            t += 32L
        }
        // Pause 1.5s (47 frames) — longer than legacy 1.2s cutoff, shorter than 2.2s adaptive short-clip patience!
        repeat(47) {
            val res = segmenter.onFrame(makeFrame(0), 0.05f, t)
            assertNull("Should NOT cut mid-thought on a 1.5s pause in a short utterance", res.completedSegment)
            t += 32L
        }
        // Continue speaking for 1.6s (50 frames)
        repeat(50) {
            val res = segmenter.onFrame(makeFrame(20), 0.85f, t)
            assertNull(res.completedSegment)
            t += 32L
        }
        // Now long pause (2.5s = 78 frames) seals ONE combined segment containing both clauses!
        var emitted: CompletedSpeechSegment? = null
        repeat(80) {
            val res = segmenter.onFrame(makeFrame(0), 0.02f, t)
            if (res.completedSegment != null) emitted = res.completedSegment
            t += 32L
        }
        assertNotNull(emitted)
        assertTrue("Combined segment should contain both clauses (>=4.5s), got ${emitted!!.durationMs}ms", emitted!!.durationMs >= 4_500L)
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
        repeat(80) {
            val res = segmenter.onFrame(makeFrame(0), 0.01f, t)
            if (res.completedSegment != null) emitted = res.completedSegment
            t += 32L
        }

        assertNull(emitted)
    }

    @Test
    fun `long speech splits cleanly at soft-window pause before hard ceiling`() {
        val cfg = defaultConfig.copy(maxSegmentDurationMs = 12_000) // soft window starts at 9.0s (281 frames)
        val segmenter = SpeechSegmenter("sess-soft", cfg)
        var t = 80_000L
        var emitted: CompletedSpeechSegment? = null

        // Speak continuously for ~9.6s (300 frames)
        repeat(300) {
            val res = segmenter.onFrame(makeFrame(55), 0.90f, t)
            if (res.completedSegment != null) emitted = res.completedSegment
            t += 32L
        }
        assertNull("Should still be active before any pause inside soft window", emitted)

        // Brief 320ms pause (10 frames) inside soft window seals cleanly without waiting for 12s mid-word cut!
        repeat(12) {
            val res = segmenter.onFrame(makeFrame(0), 0.05f, t)
            if (res.completedSegment != null) emitted = res.completedSegment
            t += 32L
        }
        assertNotNull(emitted)
        assertEquals(SegmentCloseReason.MAX_DURATION, emitted!!.closeReason)
        assertTrue(emitted!!.durationMs in 9_000L..11_000L)
    }

    @Test
    fun `forced hard-cap split preserves boundary overlap audio without dropping frames`() {
        val cfg = defaultConfig.copy(maxSegmentDurationMs = 6_400, minSpeechDurationMs = 320) // 200 frames
        val segmenter = SpeechSegmenter("sess-4", cfg)
        var t = 100_000L
        val emitted = mutableListOf<CompletedSpeechSegment>()

        // 260 continuous speech frames (~8.32s) -> forces hard split at 200 frames with 512ms overlap carried into #2.
        repeat(260) { idx ->
            val res = segmenter.onFrame(makeFrame((idx + 1).toShort()), 0.95f, t)
            res.completedSegment?.let(emitted::add)
            t += 32L
        }
        segmenter.flush()?.let(emitted::add)

        assertEquals(2, emitted.size)
        assertEquals(SegmentCloseReason.MAX_DURATION, emitted[0].closeReason)
        // Second segment starts with overlap samples matching the tail of segment 0!
        val tailSample = emitted[0].samples.last()
        assertTrue(
            "Expected overlap tail sample to appear in continuation segment head",
            emitted[1].samples.take(10_000).contains(tailSample)
        )
        assertTrue(emitted.sumOf { it.samples.size } > 260 * 512)
    }
}
