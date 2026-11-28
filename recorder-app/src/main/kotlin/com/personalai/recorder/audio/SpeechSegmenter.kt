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

/**
 * Context-preserving Silero VAD speech segmenter:
 * - Dual-threshold VAD hysteresis (`vadThreshold` to start, lower `continueThreshold` to stay active)
 *   plus 3-frame onset confirmation to reject sharp transient pops/clicks without dropping consonants.
 * - Adaptive silence patience: short utterances (<10s) wait up to 2.2s across natural thinking pauses
 *   so conversational clauses stay inside one rich-context chunk; longer chunks seal promptly at `silenceTimeoutMs`.
 * - Soft-pause split window (75%..100% of `maxSegmentDurationMs`) seals cleanly on any >=256ms inter-word pause
 *   to prevent mid-word cuts, and carries 512ms overlap audio when forced to split at the hard ceiling.
 */
class SpeechSegmenter(
    private val sessionId: String,
    private val config: RecorderConfigPayload,
    private val frameDurationMs: Int = 32,
    private val postSpeechTailMs: Int = 400,
    private val onsetConfirmFrames: Int = 3,
    private val shortClipMinSilenceMs: Int = if (config.silenceTimeoutMs >= 1_400) {
        maxOf(config.silenceTimeoutMs + 600, 2_200)
    } else {
        config.silenceTimeoutMs
    },
    private val shortClipDurationThresholdMs: Long = 10_000L,
    private val forcedSplitOverlapMs: Int = 512
) {
    private val sampleRateHz = config.sampleRateHz
    private val preRollSamplesCount = (config.preRollMs * sampleRateHz) / 1000
    private val maxSegmentSamples = (config.maxSegmentDurationMs * sampleRateHz) / 1000
    private val softMaxSegmentSamples = (maxSegmentSamples * 3) / 4
    private val minSpeechFrames = maxOf(1, config.minSpeechDurationMs / frameDurationMs)
    private val baseSilenceTimeoutFrames = maxOf(1, config.silenceTimeoutMs / frameDurationMs)
    private val shortClipSilenceTimeoutFrames = maxOf(baseSilenceTimeoutFrames, shortClipMinSilenceMs / frameDurationMs)
    private val softWindowPauseFrames = maxOf(4, 256 / frameDurationMs)
    private val postTailKeepFrames = maxOf(0, postSpeechTailMs / frameDurationMs)
    private val overlapSamplesTarget = if (config.maxSegmentDurationMs >= 5_000) {
        (forcedSplitOverlapMs * sampleRateHz) / 1000
    } else {
        0
    }

    private val speechStartThreshold = config.vadThreshold.coerceIn(0.15f, 0.95f)
    private val speechContinueThreshold = (speechStartThreshold - 0.15f).coerceAtLeast(0.22f)

    private val preRollBuffer = PreRollRingBuffer(preRollSamplesCount)
    private val pendingOnsetFrames = ArrayList<Pair<ShortArray, Long>>(onsetConfirmFrames)
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
        val frameEndMs = frameStartTimestampMs + ((pcm16.size * 1000L) / sampleRateHz)
        lastFrameEndTimestampMs = frameEndMs

        if (phase == SegmenterPhase.LISTENING) {
            val isOnsetSpeech = speechProbability >= speechStartThreshold
            if (!isOnsetSpeech) {
                if (pendingOnsetFrames.isNotEmpty()) {
                    for ((pendingPcm, _) in pendingOnsetFrames) {
                        preRollBuffer.push(pendingPcm)
                    }
                    pendingOnsetFrames.clear()
                }
                preRollBuffer.push(pcm16)
                return SegmenterStepResult(phase = SegmenterPhase.LISTENING, activeDurationMs = 0L)
            }

            pendingOnsetFrames.add(pcm16.copyOf() to frameStartTimestampMs)
            if (pendingOnsetFrames.size < onsetConfirmFrames.coerceAtLeast(1)) {
                return SegmenterStepResult(phase = SegmenterPhase.LISTENING, activeDurationMs = 0L)
            }

            // Confirmed speech onset: prepend pre-roll prior to the first pending onset frame so zero frames duplicate.
            val preRoll = preRollBuffer.drain()
            val preRollDurationMs = (preRoll.size * 1000L) / sampleRateHz
            val firstSpeechTs = pendingOnsetFrames.first().second
            activeSegmentId = UUID.randomUUID().toString()
            segmentStartTimestampMs = (firstSpeechTs - preRollDurationMs).coerceAtLeast(0L)
            activeFrames.clear()
            totalSegmentSamples = 0
            if (preRoll.isNotEmpty()) {
                activeFrames.add(preRoll)
                totalSegmentSamples += preRoll.size
            }
            for ((onsetPcm, _) in pendingOnsetFrames) {
                activeFrames.add(onsetPcm)
                totalSegmentSamples += onsetPcm.size
            }
            speechFrameCount = pendingOnsetFrames.size
            pendingOnsetFrames.clear()
            consecutiveSilenceFrames = 0
            phase = SegmenterPhase.SPEECH_ACTIVE

            return checkMaxDurationAndReturn(frameEndMs)
        }

        // Already inside SPEECH_ACTIVE or TRAILING_SILENCE: use hysteresis threshold to preserve soft syllables.
        val isContinuingSpeech = if (phase == SegmenterPhase.SPEECH_ACTIVE) {
            speechProbability >= speechContinueThreshold
        } else {
            speechProbability >= speechStartThreshold
        }

        activeFrames.add(pcm16.copyOf())
        totalSegmentSamples += pcm16.size

        if (isContinuingSpeech) {
            speechFrameCount++
            consecutiveSilenceFrames = 0
            phase = SegmenterPhase.SPEECH_ACTIVE
        } else {
            consecutiveSilenceFrames++
            phase = SegmenterPhase.TRAILING_SILENCE

            // Soft split window: if segment is already long (>=75% max) and speaker pauses briefly (>=256ms),
            // seal cleanly at the natural phrase boundary rather than chopping mid-word at max duration.
            if (config.maxSegmentDurationMs >= 10_000 &&
                totalSegmentSamples >= softMaxSegmentSamples &&
                consecutiveSilenceFrames >= softWindowPauseFrames
            ) {
                val completed = sealCurrentSegment(
                    endTimestampMs = frameEndMs,
                    reason = SegmentCloseReason.MAX_DURATION,
                    trimTrailingFrames = 0
                )
                resetToListening()
                return SegmenterStepResult(
                    phase = SegmenterPhase.LISTENING,
                    activeDurationMs = 0L,
                    completedSegment = completed
                )
            }

            // Adaptive silence patience: short clips tolerate longer thinking pauses to keep context together.
            val requiredSilenceFrames = if (currentDurationMs() < shortClipDurationThresholdMs) {
                shortClipSilenceTimeoutFrames
            } else {
                baseSilenceTimeoutFrames
            }

            if (consecutiveSilenceFrames >= requiredSilenceFrames) {
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
            pendingOnsetFrames.clear()
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
            val continuingSpeech = (phase == SegmenterPhase.SPEECH_ACTIVE)
            val overlapTail = if (continuingSpeech && overlapSamplesTarget > 0 && completed != null) {
                extractOverlapTail(completed.samples, overlapSamplesTarget)
            } else {
                ShortArray(0)
            }

            resetToListening()
            if (continuingSpeech) {
                val overlapDurMs = (overlapTail.size * 1000L) / sampleRateHz
                activeSegmentId = UUID.randomUUID().toString()
                segmentStartTimestampMs = (frameEndMs - overlapDurMs).coerceAtLeast(0L)
                phase = SegmenterPhase.SPEECH_ACTIVE
                if (overlapTail.isNotEmpty()) {
                    activeFrames.add(overlapTail)
                    totalSegmentSamples = overlapTail.size
                }
                // Do not artificially pre-seed minSpeechFrames; require actual subsequent speech so
                // trailing silence right after a forced split does not emit a ghost chunk.
                speechFrameCount = 0
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

    private fun extractOverlapTail(samples: ShortArray, targetSamples: Int): ShortArray {
        if (samples.isEmpty() || targetSamples <= 0) return ShortArray(0)
        val count = minOf(targetSamples, samples.size / 4)
        if (count <= 0) return ShortArray(0)
        return samples.copyOfRange(samples.size - count, samples.size)
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
        pendingOnsetFrames.clear()
        activeFrames.clear()
        totalSegmentSamples = 0
        speechFrameCount = 0
        consecutiveSilenceFrames = 0
        preRollBuffer.clear()
    }
}
