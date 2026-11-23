package com.personalai.recorder.audio

interface VadEngine : AutoCloseable {
    val sampleRateHz: Int
    val frameSizeSamples: Int
    fun reset()
    fun processFrame(pcm16: ShortArray): Float
}
