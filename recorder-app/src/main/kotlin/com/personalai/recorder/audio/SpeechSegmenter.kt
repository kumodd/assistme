package com.personalai.recorder.audio

import com.personalai.protocol.RecorderConfigPayload
import java.util.UUID

enum class SegmenterPhase {
    LISTENING,
    SPEECH_ACTIVE,
    TRAILING_SILENCE
}

enum class SegmentCloseReason {
    SILENCE_TIMEOUT,
    MAX_DURATION,
    MANUAL_FLUSH,
    INTERRUPTED
}

data class CompletedSpeechSegment(
    val segmentId: String,
    val sessionId: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val durationMs: Long,
    val samples: ShortArray,
    val closeReason: SegmentCloseReason
)

data class SegmenterStepResult(
    val phase: SegmenterPhase,
    val activeDurationMs: Long,
    val completedSegment: CompletedSpeechSegment? = null
)

class SpeechSegmenter(
    private val sessionId: String,
    private val config: RecorderConfigPayload,
    private val frameDurationMs: Int = 32,
    private val postSpeechTailMs: Int = 256
) {
    private val sampleRateHz = config.sampleRateHz
    private val preRollSamplesCount = (config.preRollMs * sampleRateHz) / 1000
    private val maxSegmentSamples = (config.maxSegmentDurationMs * sampleRateHz) / 1000
    private val minSpeechFrames = maxOf(1, config.minSpeechDurationMs / frameDurationMs)
    private val silenceTimeoutFrames = maxOf(1, config.silenceTimeoutMs / frameDurationMs)
    private val postTailKeepFrames = maxOf(0, postSpeechTailMs / frameDurationMs)

    private val preRollBuffer = PreRollRingBuffer(preRollSamplesCount)
    private val activeFrames = ArrayList<ShortArray>()

    private var phase: SegmenterPhase = SegmenterPhase.LISTENING
    private var activeSegmentId: String? = null
    private var segmentStartTimestampMs: Long = 0L
    private var totalSegmentSamples: Int = 0
    private var speechFrameCount: Int = 0
    private var consecutiveSilenceFrames: Int = 0
    private var lastFrameEndTimestampMs: Long = 0L

    fun onFrame(
        pcm16: ShortArray,
        speechProbability: Float,
        frameStartTimestampMs: Long
    ): SegmenterStepResult {
        val isSpeech = speechProbability >= config.vadThreshold
        val frameEndMs = frameStartTimestampMs + ((pcm16.size * 1000L) / sampleRateHz)
        lastFrameEndTimestampMs = frameEndMs

        if (phase == SegmenterPhase.LISTENING) {
            if (!isSpeech) {
                preRollBuffer.push(pcm16)
                return SegmenterStepResult(phase = SegmenterPhase.LISTENING, activeDurationMs = 0L)
            }

            // Speech onset: prepend pre-roll prior to the current frame so no duplicate frames occur.
            val preRoll = preRollBuffer.drain()
            val preRollDurationMs = (preRoll.size * 1000L) / sampleRateHz
            activeSegmentId = UUID.randomUUID().toString()
            segmentStartTimestampMs = (frameStartTimestampMs - preRollDurationMs).coerceAtLeast(0L)
            activeFrames.clear()
            if (preRoll.isNotEmpty()) {
                activeFrames.add(preRoll)
            }
            activeFrames.add(pcm16.copyOf())
            totalSegmentSamples = preRoll.size + pcm16.size
            speechFrameCount = 1
            consecutiveSilenceFrames = 0
            phase = SegmenterPhase.SPEECH_ACTIVE

            return checkMaxDurationAndReturn(frameEndMs)
        }

        // Already inside SPEECH_ACTIVE or TRAILING_SILENCE.
        activeFrames.add(pcm16.copyOf())
        totalSegmentSamples += pcm16.size

        if (isSpeech) {
            speechFrameCount++
            consecutiveSilenceFrames = 0
            phase = SegmenterPhase.SPEECH_ACTIVE
        } else {
            consecutiveSilenceFrames++
            phase = SegmenterPhase.TRAILING_SILENCE
            if (consecutiveSilenceFrames >= silenceTimeoutFrames) {
                val trimFrames = (consecutiveSilenceFrames - postTailKeepFrames).coerceAtLeast(0)
                val completed = sealCurrentSegment(
                    endTimestampMs = frameEndMs,
                    reason = SegmentCloseReason.SILENCE_TIMEOUT,
                    trimTrailingFrames = trimFrames
                )
                resetToListening()
                return SegmenterStepResult(
                    phase = SegmenterPhase.LISTENING,
                    activeDurationMs = 0L,
                    completedSegment = completed
                )
            }
        }

        return checkMaxDurationAndReturn(frameEndMs)
    }

    fun flush(
        reason: SegmentCloseReason = SegmentCloseReason.MANUAL_FLUSH,
        timestampMs: Long = lastFrameEndTimestampMs
    ): CompletedSpeechSegment? {
        if (phase == SegmenterPhase.LISTENING) {
            preRollBuffer.clear()
            return null
        }
        val trimFrames = if (consecutiveSilenceFrames > postTailKeepFrames) {
            consecutiveSilenceFrames - postTailKeepFrames
        } else {
            0
        }
        val completed = sealCurrentSegment(
            endTimestampMs = timestampMs.coerceAtLeast(segmentStartTimestampMs),
            reason = reason,
            trimTrailingFrames = trimFrames
        )
        resetToListening()
        return completed
    }

    private fun checkMaxDurationAndReturn(frameEndMs: Long): SegmenterStepResult {
        if (totalSegmentSamples >= maxSegmentSamples) {
            val completed = sealCurrentSegment(
                endTimestampMs = frameEndMs,
                reason = SegmentCloseReason.MAX_DURATION,
                trimTrailingFrames = 0
            )
            // Roll directly into a new active segment if speech is still ongoing.
            val continuingSpeech = (phase == SegmenterPhase.SPEECH_ACTIVE)
            resetToListening()
            if (continuingSpeech) {
                activeSegmentId = UUID.randomUUID().toString()
                segmentStartTimestampMs = frameEndMs
                phase = SegmenterPhase.SPEECH_ACTIVE
                // Require at least 1 speech frame on continuation (or carry partial speech cleanly).
                speechFrameCount = minSpeechFrames
            }
            return SegmenterStepResult(
                phase = phase,
                activeDurationMs = currentDurationMs(),
                completedSegment = completed
            )
        }

        return SegmenterStepResult(
            phase = phase,
            activeDurationMs = currentDurationMs()
        )
    }

    private fun sealCurrentSegment(
        endTimestampMs: Long,
        reason: SegmentCloseReason,
        trimTrailingFrames: Int
    ): CompletedSpeechSegment? {
        if (speechFrameCount < minSpeechFrames || activeFrames.isEmpty()) {
            return null
        }

        val framesToKeep = (activeFrames.size - trimTrailingFrames).coerceAtLeast(1)
        var keptSamplesCount = 0
        for (i in 0 until framesToKeep) {
            keptSamplesCount += activeFrames[i].size
        }
        if (keptSamplesCount <= 0) return null

        val merged = ShortArray(keptSamplesCount)
        var cursor = 0
        for (i in 0 until framesToKeep) {
            val chunk = activeFrames[i]
            System.arraycopy(chunk, 0, merged, cursor, chunk.size)
            cursor += chunk.size
        }

        val durationMs = (merged.size * 1000L) / sampleRateHz
        val adjustedEndMs = segmentStartTimestampMs + durationMs

        return CompletedSpeechSegment(
            segmentId = activeSegmentId ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            startTimestampMs = segmentStartTimestampMs,
            endTimestampMs = maxOf(adjustedEndMs, endTimestampMs.coerceAtMost(segmentStartTimestampMs + durationMs + 50)),
            durationMs = durationMs,
            samples = merged,
            closeReason = reason
        )
    }

    private fun currentDurationMs(): Long = (totalSegmentSamples * 1000L) / sampleRateHz

    private fun resetToListening() {
        phase = SegmenterPhase.LISTENING
        activeSegmentId = null
        segmentStartTimestampMs = 0L
        activeFrames.clear()
        totalSegmentSamples = 0
        speechFrameCount = 0
        consecutiveSilenceFrames = 0
        preRollBuffer.clear()
    }
}
