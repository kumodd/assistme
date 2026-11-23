package com.personalai.recorder.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import java.nio.LongBuffer

class SileroOnnxVadEngine(
    modelBytes: ByteArray,
    override val sampleRateHz: Int = 16_000,
    override val frameSizeSamples: Int = 512
) : VadEngine {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val contextSize: Int = if (sampleRateHz == 16_000) 64 else 32
    private val srShape: LongArray

    private var state = FloatArray(STATE_ELEMENTS)
    private var context = FloatArray(contextSize)
    private val inputWorkBuffer = FloatArray(contextSize + frameSizeSamples)
    private val lock = Any()

    init {
        require(sampleRateHz == 16_000 || sampleRateHz == 8_000) {
            "Silero VAD supports 16000 Hz or 8000 Hz, got $sampleRateHz"
        }
        val expectedFrameSize = if (sampleRateHz == 16_000) 512 else 256
        require(frameSizeSamples == expectedFrameSize) {
            "Silero VAD expects $expectedFrameSize samples at $sampleRateHz Hz, got $frameSizeSamples"
        }

        val options = OrtSession.SessionOptions().apply {
            setInterOpNumThreads(1)
            setIntraOpNumThreads(1)
        }
        session = env.createSession(modelBytes, options)

        val srInfo = session.inputInfo["sr"]?.info as? TensorInfo
        val rank = srInfo?.shape?.size ?: 0
        srShape = if (rank == 1) longArrayOf(1L) else longArrayOf()
    }

    override fun reset() = synchronized(lock) {
        state.fill(0f)
        context.fill(0f)
    }

    override fun processFrame(pcm16: ShortArray): Float = synchronized(lock) {
        require(pcm16.size == frameSizeSamples) {
            "Expected $frameSizeSamples samples, got ${pcm16.size}"
        }

        // Prepend previous context samples followed by normalized PCM [-1f, 1f].
        System.arraycopy(context, 0, inputWorkBuffer, 0, contextSize)
        for (i in 0 until frameSizeSamples) {
            inputWorkBuffer[contextSize + i] = pcm16[i] / 32768.0f
        }
        System.arraycopy(inputWorkBuffer, frameSizeSamples, context, 0, contextSize)

        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputWorkBuffer),
            longArrayOf(1L, (contextSize + frameSizeSamples).toLong())
        )
        val stateTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(state),
            longArrayOf(2L, 1L, 128L)
        )
        val srTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(sampleRateHz.toLong())),
            srShape
        )

        try {
            val inputs = mapOf(
                "input" to inputTensor,
                "state" to stateTensor,
                "sr" to srTensor
            )
            session.run(inputs).use { results ->
                val outProb = extractProbability(results[0].value)
                extractState(results[1].value, state)
                return outProb.coerceIn(0f, 1f)
            }
        } finally {
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
        }
    }

    override fun close() = synchronized(lock) {
        session.close()
    }

    private fun extractProbability(value: Any?): Float {
        return when (value) {
            is Array<*> -> {
                val inner = value.firstOrNull()
                if (inner is FloatArray) inner.firstOrNull() ?: 0f else 0f
            }
            is FloatArray -> value.firstOrNull() ?: 0f
            else -> 0f
        }
    }

    private fun extractState(value: Any?, dest: FloatArray) {
        if (value !is Array<*>) return
        var offset = 0
        for (layer in value) {
            val batches = layer as? Array<*> ?: continue
            for (batch in batches) {
                val hidden = batch as? FloatArray ?: continue
                System.arraycopy(hidden, 0, dest, offset, hidden.size)
                offset += hidden.size
            }
        }
    }

    companion object {
        private const val STATE_ELEMENTS = 2 * 1 * 128
    }
}
