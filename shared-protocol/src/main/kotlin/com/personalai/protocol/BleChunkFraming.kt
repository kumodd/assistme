package com.personalai.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

object BleChunkFraming {
    const val FRAME_HEADER_SIZE = 6 // msgId(2) + chunkIndex(2) + totalChunks(2)
    const val DEFAULT_ATT_MTU = 247
    const val MIN_ATT_MTU = 23
    /**
     * Bluetooth Core Spec Vol 3 Part F Section 3.2.9 caps any single GATT attribute value /
     * notification at 512 octets even when Android 14+ negotiates ATT MTU = 517 (517 - 3 = 514).
     * Capping frames at 500 bytes guarantees zero silent truncation across all Android chipsets.
     */
    const val MAX_GATT_VALUE_BYTES = 500

    fun split(
        messageBytes: ByteArray,
        attMtu: Int,
        msgId: Int
    ): List<ByteArray> {
        val maxAttrWriteBytes = (attMtu - 3).coerceIn(MIN_ATT_MTU - 3, MAX_GATT_VALUE_BYTES)
        val maxSliceSize = (maxAttrWriteBytes - FRAME_HEADER_SIZE).coerceAtLeast(1)
        val totalChunks = maxOf(1, (messageBytes.size + maxSliceSize - 1) / maxSliceSize)
        require(totalChunks <= 65_535) { "Message exceeds max chunk count: $totalChunks" }

        val frames = ArrayList<ByteArray>(totalChunks)
        for (chunkIdx in 0 until totalChunks) {
            val start = chunkIdx * maxSliceSize
            val end = minOf(messageBytes.size, start + maxSliceSize)
            val sliceLen = end - start
            val buf = ByteBuffer.allocate(FRAME_HEADER_SIZE + sliceLen).order(ByteOrder.BIG_ENDIAN)
            buf.putShort((msgId and 0xFFFF).toShort())
            buf.putShort((chunkIdx and 0xFFFF).toShort())
            buf.putShort((totalChunks and 0xFFFF).toShort())
            buf.put(messageBytes, start, sliceLen)
            frames.add(buf.array())
        }
        return frames
    }
}

class MessageIdGenerator {
    private val counter = AtomicInteger(1)
    fun next(): Int = counter.getAndIncrement() and 0xFFFF
}

class PacketReassembler(
    private val maxInFlightMessages: Int = 16,
    private val messageTimeoutMs: Long = 15_000L
) {
    private data class AssemblyBuffer(
        val totalChunks: Int,
        val createdAtMs: Long,
        val chunks: Array<ByteArray?>
    ) {
        var receivedCount: Int = 0
    }

    private val inFlight = LinkedHashMap<Int, AssemblyBuffer>()
    private val lock = Any()

    fun acceptFrame(frame: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray? = synchronized(lock) {
        if (frame.size < BleChunkFraming.FRAME_HEADER_SIZE) return null

        pruneExpiredLocked(nowMs)

        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val msgId = buf.short.toInt() and 0xFFFF
        val chunkIndex = buf.short.toInt() and 0xFFFF
        val totalChunks = buf.short.toInt() and 0xFFFF

        if (totalChunks <= 0 || chunkIndex >= totalChunks) return null

        val slice = ByteArray(frame.size - BleChunkFraming.FRAME_HEADER_SIZE)
        buf.get(slice)

        if (totalChunks == 1 && chunkIndex == 0) {
            return slice
        }

        val entry = inFlight.getOrPut(msgId) {
            if (inFlight.size >= maxInFlightMessages) {
                val oldestKey = inFlight.keys.firstOrNull()
                if (oldestKey != null) inFlight.remove(oldestKey)
            }
            AssemblyBuffer(totalChunks = totalChunks, createdAtMs = nowMs, chunks = arrayOfNulls(totalChunks))
        }

        if (entry.totalChunks != totalChunks) {
            inFlight.remove(msgId)
            return null
        }

        if (entry.chunks[chunkIndex] == null) {
            entry.chunks[chunkIndex] = slice
            entry.receivedCount++
        }

        if (entry.receivedCount == entry.totalChunks) {
            inFlight.remove(msgId)
            val out = ByteArrayOutputStream()
            for (part in entry.chunks) {
                out.write(part ?: return null)
            }
            return out.toByteArray()
        }
        return null
    }

    fun clear() = synchronized(lock) {
        inFlight.clear()
    }

    private fun pruneExpiredLocked(nowMs: Long) {
        val it = inFlight.entries.iterator()
        while (it.hasNext()) {
            val (_, value) = it.next()
            if (nowMs - value.createdAtMs > messageTimeoutMs) {
                it.remove()
            }
        }
    }
}
