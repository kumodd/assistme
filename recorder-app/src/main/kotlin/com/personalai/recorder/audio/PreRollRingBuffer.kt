package com.personalai.recorder.audio

class PreRollRingBuffer(
    private val capacitySamples: Int
) {
    private val buffer = ShortArray(capacitySamples.coerceAtLeast(1))
    private var writePos = 0
    private var totalWritten = 0

    fun push(samples: ShortArray) {
        if (capacitySamples <= 0 || samples.isEmpty()) return
        if (samples.size >= capacitySamples) {
            val start = samples.size - capacitySamples
            System.arraycopy(samples, start, buffer, 0, capacitySamples)
            writePos = 0
            totalWritten = capacitySamples
            return
        }
        val firstPart = minOf(samples.size, capacitySamples - writePos)
        System.arraycopy(samples, 0, buffer, writePos, firstPart)
        val remaining = samples.size - firstPart
        if (remaining > 0) {
            System.arraycopy(samples, firstPart, buffer, 0, remaining)
        }
        writePos = (writePos + samples.size) % capacitySamples
        totalWritten = minOf(capacitySamples, totalWritten + samples.size)
    }

    fun drain(): ShortArray {
        val count = totalWritten
        if (count == 0 || capacitySamples <= 0) return ShortArray(0)
        val out = ShortArray(count)
        if (totalWritten < capacitySamples) {
            System.arraycopy(buffer, 0, out, 0, count)
        } else {
            val firstPart = capacitySamples - writePos
            System.arraycopy(buffer, writePos, out, 0, firstPart)
            if (writePos > 0) {
                System.arraycopy(buffer, 0, out, firstPart, writePos)
            }
        }
        clear()
        return out
    }

    fun clear() {
        writePos = 0
        totalWritten = 0
    }
}
