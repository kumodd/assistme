package com.personalai.recorder.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.L2capSyncFraming
import com.personalai.recorder.data.db.DiagnosticSeverity
import com.personalai.recorder.data.repo.RecorderRepository
import com.personalai.recorder.domain.state.RecorderStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves encrypted WAV segment payloads over BLE L2CAP Connection-Oriented Channels (API 29+)
 * and maintains a bounded single-segment decrypted cache for GATT chunk fallback transfers.
 */
@Singleton
class L2capSyncServer @Inject constructor(
    private val repository: RecorderRepository,
    private val stateMachine: RecorderStateMachine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null

    private var cachedSegmentId: String? = null
    private var cachedWavBytes: ByteArray? = null

    @SuppressLint("MissingPermission")
    fun openChannelForSession(
        adapter: BluetoothAdapter,
        sessionEncryptionKey: ByteArray
    ): Int? {
        closeChannel()
        // Payload is end-to-end AES-256-GCM encrypted with ephemeral ECDH sessionEncryptionKey;
        // insecure L2CAP CoC avoids OS SMP bond-state failures across Android OEM stacks.
        val socket = runCatching { adapter.listenUsingInsecureL2capChannel() }
            .onFailure { android.util.Log.w("L2capSyncServer", "listenUsingInsecureL2capChannel failed: ${it.message}") }
            .getOrNull() ?: return null
        val psm = socket.psm
        android.util.Log.d("L2capSyncServer", "Opened L2CAP CoC server on PSM=$psm")
        synchronized(lock) {
            serverSocket = socket
        }

        acceptJob = scope.launch {
            try {
                val clientSocket = socket.accept(45_000)
                stateMachine.updateBleState(BleConnectionState.SYNCHRONIZING)
                clientSocket.use { sock ->
                    val input = sock.inputStream
                    val output = sock.outputStream
                    while (isActive) {
                        val req = runCatching { L2capSyncFraming.readPullRequest(input) }.getOrNull()
                            ?: break
                        val wavRes = loadOrCacheWav(req.segmentId)
                        if (wavRes.isFailure) {
                            val errStatus = if (wavRes.exceptionOrNull() is NoSuchElementException) {
                                L2capSyncFraming.STATUS_NOT_FOUND
                            } else {
                                L2capSyncFraming.STATUS_CORRUPT
                            }
                            L2capSyncFraming.writeErrorStatus(output, errStatus)
                            continue
                        }
                        val wavBytes = wavRes.getOrThrow()
                        L2capSyncFraming.streamEncryptedSegment(
                            out = output,
                            segmentId = req.segmentId,
                            wavBytes = wavBytes,
                            resumeOffsetBytes = req.resumeOffsetBytes,
                            sessionEncryptionKey = sessionEncryptionKey
                        )
                    }
                }
            } catch (t: Throwable) {
                repository.logEvent(
                    category = "BLE_L2CAP",
                    severity = DiagnosticSeverity.WARNING,
                    message = "L2CAP sync session ended: ${t.message}"
                )
            } finally {
                if (stateMachine.bleState.value == BleConnectionState.SYNCHRONIZING) {
                    stateMachine.updateBleState(BleConnectionState.READY)
                }
                closeSocketOnly()
            }
        }
        return psm
    }

    suspend fun readChunkForGattFallback(
        segmentId: String,
        byteOffset: Long,
        maxBytes: Int
    ): Result<Triple<ByteArray, Long, Boolean>> {
        val wavRes = loadOrCacheWav(segmentId)
        if (wavRes.isFailure) return Result.failure(wavRes.exceptionOrNull()!!)
        val wav = wavRes.getOrThrow()
        val total = wav.size.toLong()
        val start = byteOffset.toInt().coerceIn(0, wav.size)
        val end = minOf(wav.size, start + maxBytes.coerceIn(256, 8_192))
        val slice = wav.copyOfRange(start, end)
        val eof = end >= wav.size
        return Result.success(Triple(slice, total, eof))
    }

    fun evictCachedSegment(segmentId: String) = synchronized(lock) {
        if (cachedSegmentId == segmentId) {
            cachedSegmentId = null
            cachedWavBytes = null
        }
    }

    fun closeChannel() {
        acceptJob?.cancel()
        acceptJob = null
        closeSocketOnly()
        synchronized(lock) {
            cachedSegmentId = null
            cachedWavBytes = null
        }
    }

    private fun closeSocketOnly() = synchronized(lock) {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun loadOrCacheWav(segmentId: String): Result<ByteArray> {
        synchronized(lock) {
            if (cachedSegmentId == segmentId && cachedWavBytes != null) {
                return Result.success(cachedWavBytes!!)
            }
        }
        val res = repository.readDecryptedSegmentWavForSync(segmentId)
        if (res.isSuccess) {
            synchronized(lock) {
                cachedSegmentId = segmentId
                cachedWavBytes = res.getOrThrow()
            }
        }
        return res
    }
}
