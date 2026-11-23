package com.personalai.primary.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Deterministic multilingual (English + Hindi Devanagari + Hinglish) local embedding encoder
 * using L2-normalized signed word + character trigram feature hashing. Runs completely offline
 * with zero model downloads and gives reliable local semantic similarity alongside Room FTS4.
 */
object LocalSemanticEmbedder {
    const val MODEL_ID = "local-multilingual-hash-256-v1"
    const val DIMENSIONS = 256

    fun embed(text: String): FloatArray {
        val vec = FloatArray(DIMENSIONS)
        val normalized = normalizeText(text)
        if (normalized.isEmpty()) return vec

        val tokens = normalized.split(' ').filter { it.length >= 2 }
        for (token in tokens) {
            // Whole word feature (higher weight)
            addFeature(vec, "w:$token", 2.0f)
            // Character 3-grams handle morphology, Hindi stems, and typo proximity
            val padded = "^$token$"
            if (padded.length >= 3) {
                for (i in 0..padded.length - 3) {
                    addFeature(vec, "g:${padded.substring(i, i + 3)}", 1.0f)
                }
            }
        }
        // Bigrams for short phrase intent matching ("next week", "follow up", "business idea")
        for (i in 0 until tokens.size - 1) {
            addFeature(vec, "b:${tokens[i]}_${tokens[i + 1]}", 1.5f)
        }

        normalizeInPlace(vec)
        return vec
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return dot.coerceIn(-1f, 1f)
    }

    fun toByteArray(vector: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) buf.putFloat(v)
        return buf.array()
    }

    fun fromByteArray(bytes: ByteArray): FloatArray {
        val count = bytes.size / 4
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buf.float }
    }

    private fun normalizeText(raw: String): String {
        val sb = StringBuilder(raw.length)
        var lastSpace = true
        for (ch in raw.lowercase()) {
            val keep = ch.isLetterOrDigit() || ch in '\u0900'..'\u097F'
            if (keep) {
                sb.append(ch)
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        return sb.toString().trim()
    }

    private fun addFeature(vec: FloatArray, key: String, weight: Float) {
        val h = fnv1a32(key)
        val idx = (h and 0x7FFFFFFF) % DIMENSIONS
        val sign = if ((h ushr 31) == 0) 1f else -1f
        vec[idx] += sign * weight
    }

    private fun fnv1a32(input: String): Int {
        var hash = -0x7ee3623b // 0x811c9dc5
        for (ch in input) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return hash
    }

    private fun normalizeInPlace(vec: FloatArray) {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        if (sumSq <= 1e-8f) return
        val invNorm = 1f / sqrt(sumSq)
        for (i in vec.indices) vec[i] *= invNorm
    }
}
