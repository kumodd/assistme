package com.personalai.primary.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.personalai.primary.data.db.CommandAuditDao
import com.personalai.primary.data.db.CommandAuditEntity
import com.personalai.primary.data.db.PairedRecorderDao
import com.personalai.primary.data.db.PairedRecorderEntity
import com.personalai.primary.permissions.PrimaryPermissionManager
import com.personalai.primary.security.PrimaryCredentialsStore
import com.personalai.protocol.AcknowledgementPayload
import com.personalai.protocol.AuthChallengePayload
import com.personalai.protocol.AuthVerifyPayload
import com.personalai.protocol.BatteryInfoPayload
import com.personalai.protocol.BleChunkFraming
import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.BleMessageType
import com.personalai.protocol.BleProtocolConstants
import com.personalai.protocol.CommandType
import com.personalai.protocol.DeviceInfoPayload
import com.personalai.protocol.FetchSegmentChunkRequestPayload
import com.personalai.protocol.FetchSegmentChunkResponsePayload
import com.personalai.protocol.MessageIdGenerator
import com.personalai.protocol.PacketReassembler
import com.personalai.protocol.PairingExchangeRequestPayload
import com.personalai.protocol.PairingExchangeResponsePayload
import com.personalai.protocol.PingPayload
import com.personalai.protocol.PingResponsePayload
import com.personalai.protocol.ProtocolCrypto
import com.personalai.protocol.ProtocolEnvelope
import com.personalai.protocol.ProtocolErrorCode
import com.personalai.protocol.RecorderConfigPayload
import com.personalai.protocol.RecorderStatusPayload
import com.personalai.protocol.SessionKeys
import com.personalai.protocol.StartRecordingPayload
import com.personalai.protocol.StatusEventType
import com.personalai.protocol.StopMode
import com.personalai.protocol.StopRecordingPayload
import com.personalai.protocol.StorageInfoPayload
import com.personalai.protocol.SyncAckPayload
import com.personalai.protocol.SyncDataRequestPayload
import com.personalai.protocol.SyncManifestSummaryPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class PendingSasConfirmation(
    val bleAddress: String,
    val serverDeviceId: String,
    val serverDeviceName: String,
    val sasCode: String,
    val derivedMasterKey: ByteArray,
    val firmwareVersion: String
)

data class CommandFeedback(
    val commandType: CommandType,
    val success: Boolean,
    val errorCode: ProtocolErrorCode,
    val message: String,
    val rttMs: Long
)

@Singleton
class BleClientController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: PrimaryPermissionManager,
    private val credentialsStore: PrimaryCredentialsStore,
    private val pairedDao: PairedRecorderDao,
    private val auditDao: CommandAuditDao,
    private val scannerManager: BleScannerManager
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()
    private val msgIdGenerator = MessageIdGenerator()
    private val gattOpMutex = Mutex()
    private val commandMutex = Mutex()
    private val sequenceGenerator = AtomicLong(1L)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _bleState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val bleState: StateFlow<BleConnectionState> = _bleState.asStateFlow()

    private val _remoteStatus = MutableStateFlow<RecorderStatusPayload?>(null)
    val remoteStatus: StateFlow<RecorderStatusPayload?> = _remoteStatus.asStateFlow()

    private val _remoteBattery = MutableStateFlow<BatteryInfoPayload?>(null)
    val remoteBattery: StateFlow<BatteryInfoPayload?> = _remoteBattery.asStateFlow()

    private val _remoteStorage = MutableStateFlow<StorageInfoPayload?>(null)
    val remoteStorage: StateFlow<StorageInfoPayload?> = _remoteStorage.asStateFlow()

    private val _remoteDeviceInfo = MutableStateFlow<DeviceInfoPayload?>(null)
    val remoteDeviceInfo: StateFlow<DeviceInfoPayload?> = _remoteDeviceInfo.asStateFlow()

    private val _remoteConfig = MutableStateFlow<RecorderConfigPayload?>(null)
    val remoteConfig: StateFlow<RecorderConfigPayload?> = _remoteConfig.asStateFlow()

    private val _pendingSas = MutableStateFlow<PendingSasConfirmation?>(null)
    val pendingSas: StateFlow<PendingSasConfirmation?> = _pendingSas.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _commandFeedback = MutableSharedFlow<CommandFeedback>(extraBufferCapacity = 32)
    val commandFeedback: SharedFlow<CommandFeedback> = _commandFeedback.asSharedFlow()

    private var activeGatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var negotiatedMtu: Int = BleChunkFraming.MIN_ATT_MTU

    private var charCommand: BluetoothGattCharacteristic? = null
    private var charStatus: BluetoothGattCharacteristic? = null
    private var charConfig: BluetoothGattCharacteristic? = null
    private var charAck: BluetoothGattCharacteristic? = null
    private var charSync: BluetoothGattCharacteristic? = null
    private var charDeviceInfo: BluetoothGattCharacteristic? = null

    private val statusReassembler = PacketReassembler()
    private val ackReassembler = PacketReassembler()
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<AcknowledgementPayload>>()

    private var servicesDeferred: CompletableDeferred<Boolean>? = null
    private var mtuDeferred: CompletableDeferred<Int>? = null
    private var descriptorWriteDeferred: CompletableDeferred<Int>? = null
    private var characteristicWriteDeferred: CompletableDeferred<Int>? = null
    private var characteristicReadDeferred: CompletableDeferred<Pair<Int, ByteArray>>? = null

    private var sessionKeys: SessionKeys? = null
    private var sessionContext: ByteArray = ByteArray(0)
    private var pendingAuthChallenge: AuthChallengePayload? = null
    private var pairingKeyPair: KeyPair? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var receiverRegistered = false

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action ?: return
            if (Build.VERSION.SDK_INT >= 36 && action == BluetoothDevice.ACTION_KEY_MISSING) {
                _lastError.value = "Bluetooth bond key missing on peer. Re-pair if reconnection fails."
                disconnect(userInitiated = false)
            } else if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (state == BluetoothDevice.BOND_NONE) {
                    _lastError.value = "Bluetooth bond cleared; re-pair may be required."
                }
            }
        }
    }

    init {
        registerBondReceiver()
        scope.launch {
            val paired = pairedDao.getPairedDevice()
            if (paired != null) {
                _remoteStatus.value = RecorderStatusPayload(
                    recorderState = paired.lastKnownRecorderState,
                    activeSessionId = null,
                    batteryPct = paired.lastBatteryPct,
                    isCharging = false,
                    thermalStatus = 0,
                    freeStorageBytes = paired.lastFreeStorageBytes,
                    totalStorageBytes = paired.lastTotalStorageBytes,
                    pendingSyncSegments = paired.lastPendingSyncCount,
                    currentSessionSegments = 0,
                    lastSyncTimestampMs = paired.lastSyncTimestampMs,
                    timestampMs = paired.lastConnectedAtMs ?: paired.pairedAtMs
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun beginPairing(address: String) {
        scope.launch {
            if (!permissionManager.hasBlePermissions()) {
                _lastError.value = "Bluetooth permissions missing."
                return@launch
            }
            manualDisconnect = false
            _lastError.value = null
            _pendingSas.value = null
            val connected = connectInternal(address, autoConnect = false)
            if (!connected) {
                _lastError.value = "Failed connecting GATT to $address"
                return@launch
            }

            val kp = ProtocolCrypto.generateEcKeyPair()
            pairingKeyPair = kp
            val identity = credentialsStore.getOrCreateIdentity()
            val req = PairingExchangeRequestPayload(
                clientDeviceId = identity.clientDeviceId,
                clientDeviceName = identity.clientDeviceName,
                clientPublicKeyBase64 = ProtocolCrypto.encodePublicKeyBase64(kp.public)
            )
            val ack = sendEnvelopeAndAwaitAck(
                commandType = CommandType.PAIR_EXCHANGE,
                payloadBytes = json.encodeToString(req).toByteArray(Charsets.UTF_8),
                authenticated = false
            )
            if (ack == null || !ack.success || ack.dataJson.isNullOrBlank()) {
                _lastError.value = ack?.message ?: "Pairing exchange failed or timed out"
                return@launch
            }

            val resp = runCatching {
                json.decodeFromString<PairingExchangeResponsePayload>(ack.dataJson!!)
            }.getOrNull() ?: run {
                _lastError.value = "Invalid pairing response payload"
                return@launch
            }

            val serverPub = ProtocolCrypto.decodePublicKeyBase64(resp.serverPublicKeyBase64)
            val sharedSecret = ProtocolCrypto.computeEcdhSharedSecret(kp.private, serverPub)
            val derivedMasterKey = ProtocolCrypto.deriveMasterKey(
                sharedSecret = sharedSecret,
                clientDeviceId = identity.clientDeviceId,
                serverDeviceId = resp.serverDeviceId
            )
            val sasCode = ProtocolCrypto.computeSixDigitSas(
                sharedSecret = sharedSecret,
                clientPubEncoded = kp.public.encoded,
                serverPubEncoded = serverPub.encoded,
                serverDeviceId = resp.serverDeviceId
            )

            if (sasCode != resp.sasCode) {
                _lastError.value = "SAS verification mismatch! Aborting pairing."
                disconnect(userInitiated = true)
                return@launch
            }

            val info = readDeviceInfoSnapshot()
            _pendingSas.value = PendingSasConfirmation(
                bleAddress = address,
                serverDeviceId = resp.serverDeviceId,
                serverDeviceName = resp.serverDeviceName,
                sasCode = sasCode,
                derivedMasterKey = derivedMasterKey,
                firmwareVersion = info?.firmwareVersion ?: "0.2.0"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun confirmSasAndSavePairing() {
        val pending = _pendingSas.value ?: return
        scope.launch {
            credentialsStore.savePairedMasterKey(pending.serverDeviceId, pending.derivedMasterKey)
            pairedDao.upsert(
                PairedRecorderEntity(
                    deviceId = pending.serverDeviceId,
                    deviceName = pending.serverDeviceName,
                    bleAddress = pending.bleAddress,
                    firmwareVersion = pending.firmwareVersion,
                    pairedAtMs = System.currentTimeMillis(),
                    lastConnectedAtMs = System.currentTimeMillis()
                )
            )
            _pendingSas.value = null

            pendingAuthChallenge?.let { challenge ->
                pendingAuthChallenge = null
                respondToAuthChallenge(challenge)
            }

            activeGatt?.device?.let { dev ->
                if (dev.bondState == BluetoothDevice.BOND_NONE) {
                    runCatching { dev.createBond() }
                }
            }
        }
    }

    fun cancelPendingPairing() {
        _pendingSas.value = null
        pairingKeyPair = null
        disconnect(userInitiated = true)
    }

    fun connectToPairedRecorder(autoConnect: Boolean = false) {
        scope.launch {
            val paired = pairedDao.getPairedDevice()
            if (paired == null) {
                _lastError.value = "No paired Device B found."
                return@launch
            }
            manualDisconnect = false
            _lastError.value = null
            if (connectInternal(paired.bleAddress, autoConnect = autoConnect)) {
                return@launch
            }
            // If direct address failed (e.g. RPA MAC rotated), fall back to app-level deviceTag scan.
            val refreshedAddress = resolveAddressByAppDeviceTag(paired.deviceId)
            if (refreshedAddress != null && refreshedAddress != paired.bleAddress) {
                pairedDao.upsert(paired.copy(bleAddress = refreshedAddress))
                connectInternal(refreshedAddress, autoConnect = false)
            }
        }
    }

    private suspend fun resolveAddressByAppDeviceTag(serverDeviceId: String): String? {
        val expectedTagHex = ProtocolCrypto.deviceTag8Bytes(serverDeviceId)
            .joinToString("") { "%02x".format(it) }
        scannerManager.startScan()
        val match = withTimeoutOrNull(6_000L) {
            var found: String? = null
            while (found == null) {
                found = scannerManager.discovered.value
                    .firstOrNull { it.deviceTagHex == expectedTagHex }
                    ?.address
                if (found == null) delay(250L)
            }
            found
        }
        scannerManager.stopScan()
        return match
    }

    fun unpair() {
        scope.launch {
            disconnect(userInitiated = true)
            credentialsStore.clearPairing()
            pairedDao.deleteAll()
            _remoteStatus.value = null
            _remoteBattery.value = null
            _remoteStorage.value = null
            _remoteDeviceInfo.value = null
            _remoteConfig.value = null
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(userInitiated: Boolean = true) {
        manualDisconnect = userInitiated
        reconnectJob?.cancel()
        reconnectJob = null
        sessionKeys = null
        sessionContext = ByteArray(0)
        pendingAuthChallenge = null
        statusReassembler.clear()
        ackReassembler.clear()
        pendingAcks.values.forEach { it.cancel() }
        pendingAcks.clear()

        activeGatt?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        activeGatt = null
        _bleState.value = BleConnectionState.DISCONNECTED
    }

    fun startRecording() = launchCommand(
        CommandType.START_RECORDING,
        json.encodeToString(StartRecordingPayload())
    )

    fun stopRecording(mode: StopMode) = launchCommand(
        CommandType.STOP_RECORDING,
        json.encodeToString(StopRecordingPayload(mode))
    )

    fun refreshStatus() = launchCommand(CommandType.GET_STATUS, "{}") { ack ->
        ack.dataJson?.let { raw ->
            runCatching { json.decodeFromString<RecorderStatusPayload>(raw) }.getOrNull()?.let { st ->
                _remoteStatus.value = st
                scope.launch { persistTelemetrySnapshot(st) }
            }
        }
    }

    fun refreshBattery() = launchCommand(CommandType.GET_BATTERY, "{}") { ack ->
        ack.dataJson?.let { raw ->
            runCatching { json.decodeFromString<BatteryInfoPayload>(raw) }.getOrNull()?.let {
                _remoteBattery.value = it
            }
        }
    }

    fun refreshStorage() = launchCommand(CommandType.GET_STORAGE, "{}") { ack ->
        ack.dataJson?.let { raw ->
            runCatching { json.decodeFromString<StorageInfoPayload>(raw) }.getOrNull()?.let {
                _remoteStorage.value = it
            }
        }
    }

    fun refreshDeviceInfo() = launchCommand(CommandType.GET_DEVICE_INFO, "{}") { ack ->
        ack.dataJson?.let { raw ->
            runCatching { json.decodeFromString<DeviceInfoPayload>(raw) }.getOrNull()?.let {
                _remoteDeviceInfo.value = it
            }
        }
    }

    fun requestSyncSummary() = launchCommand(
        CommandType.SYNC_DATA,
        json.encodeToString(SyncDataRequestPayload())
    ) { ack ->
        ack.dataJson?.let { raw ->
            runCatching { json.decodeFromString<SyncManifestSummaryPayload>(raw) }.getOrNull()?.let { summary ->
                scope.launch {
                    _commandFeedback.emit(
                        CommandFeedback(
                            commandType = CommandType.SYNC_DATA,
                            success = ack.success,
                            errorCode = ack.errorCode,
                            message = summary.message,
                            rttMs = 0L
                        )
                    )
                }
            }
        }
    }

    fun ping() = launchCommand(
        CommandType.PING,
        json.encodeToString(PingPayload(System.currentTimeMillis()))
    ) { ack ->
        ack.dataJson?.let { raw ->
            runCatching { json.decodeFromString<PingResponsePayload>(raw) }.getOrNull()?.let { resp ->
                val rtt = (System.currentTimeMillis() - resp.clientTimeMs).coerceAtLeast(0L)
                scope.launch {
                    _commandFeedback.emit(
                        CommandFeedback(
                            commandType = CommandType.PING,
                            success = true,
                            errorCode = ProtocolErrorCode.NONE,
                            message = "PONG (state=${resp.recorderState}, RTT=${rtt}ms)",
                            rttMs = rtt
                        )
                    )
                }
            }
        }
    }

    fun setConfig(config: RecorderConfigPayload) = launchCommand(
        CommandType.SET_CONFIG,
        json.encodeToString(config)
    ) { ack ->
        if (ack.success) {
            _remoteConfig.value = config
            refreshStatus()
        }
    }

    fun setLocalOnlyPauseSync(paused: Boolean) {
        val current = _remoteConfig.value ?: RecorderConfigPayload()
        setConfig(current.copy(localOnlyPauseSync = paused))
    }

    fun getSessionEncryptionKey(): ByteArray? = sessionKeys?.encryptionKey?.copyOf()

    fun updateSyncBleState(state: BleConnectionState) {
        if (state == BleConnectionState.SYNCHRONIZING ||
            state == BleConnectionState.SYNC_ERROR ||
            (state == BleConnectionState.READY &&
                (_bleState.value == BleConnectionState.SYNCHRONIZING || _bleState.value == BleConnectionState.SYNC_ERROR))
        ) {
            _bleState.value = state
        }
    }

    @SuppressLint("MissingPermission")
    fun openL2capSocket(psm: Int): Result<BluetoothSocket> {
        if (!permissionManager.hasBlePermissions()) {
            return Result.failure(SecurityException("Missing BLUETOOTH_CONNECT permission"))
        }
        val device = activeGatt?.device
            ?: return Result.failure(IllegalStateException("No active BLE device for L2CAP channel"))
        return runCatching {
            // Payload is already end-to-end AES-256-GCM encrypted with ephemeral ECDH session key;
            // insecure L2CAP CoC avoids SMP bond-state stalls across Android OEM stacks.
            val socket = device.createInsecureL2capChannel(psm)
            socket.connect()
            socket
        }
    }

    suspend fun requestSyncManifest(
        cursorSequence: Long,
        maxBatch: Int = 15,
        preferL2cap: Boolean = true
    ): Result<SyncManifestSummaryPayload> {
        val ack = sendCommandWithBoundedRetries(
            type = CommandType.SYNC_DATA,
            payloadBytes = json.encodeToString(
                SyncDataRequestPayload(
                    cursorSequence = cursorSequence,
                    maxBatch = maxBatch,
                    preferL2cap = preferL2cap
                )
            ).toByteArray(Charsets.UTF_8),
            allowDuringSync = true
        ) ?: return Result.failure(IllegalStateException("SYNC_DATA timed out"))

        if (!ack.success || ack.dataJson.isNullOrBlank()) {
            return Result.failure(IllegalStateException(ack.message.ifBlank { ack.errorCode.name }))
        }
        return runCatching { json.decodeFromString<SyncManifestSummaryPayload>(ack.dataJson!!) }
    }

    suspend fun fetchSegmentChunkOverGatt(
        segmentId: String,
        byteOffset: Long,
        maxBytes: Int = 2_048
    ): Result<FetchSegmentChunkResponsePayload> {
        val ack = sendCommandWithBoundedRetries(
            type = CommandType.FETCH_SEGMENT_CHUNK,
            payloadBytes = json.encodeToString(
                FetchSegmentChunkRequestPayload(
                    segmentId = segmentId,
                    byteOffset = byteOffset,
                    maxBytes = maxBytes
                )
            ).toByteArray(Charsets.UTF_8),
            allowDuringSync = true
        ) ?: return Result.failure(IllegalStateException("FETCH_SEGMENT_CHUNK timed out"))

        if (!ack.success || ack.dataJson.isNullOrBlank()) {
            return Result.failure(IllegalStateException(ack.message.ifBlank { ack.errorCode.name }))
        }
        return runCatching { json.decodeFromString<FetchSegmentChunkResponsePayload>(ack.dataJson!!) }
    }

    suspend fun sendSyncAck(
        segmentId: String,
        sequenceNumber: Long,
        verifiedSha256: String
    ): Result<Unit> {
        val ack = sendCommandWithBoundedRetries(
            type = CommandType.SYNC_ACK,
            payloadBytes = json.encodeToString(
                SyncAckPayload(
                    segmentId = segmentId,
                    sequenceNumber = sequenceNumber,
                    verifiedSha256 = verifiedSha256
                )
            ).toByteArray(Charsets.UTF_8),
            allowDuringSync = true
        ) ?: return Result.failure(IllegalStateException("SYNC_ACK timed out"))

        return if (ack.success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(ack.message.ifBlank { ack.errorCode.name }))
        }
    }

    suspend fun markLastSyncCompleted(timestampMs: Long) {
        val paired = pairedDao.getPairedDevice() ?: return
        pairedDao.upsert(paired.copy(lastSyncTimestampMs = timestampMs))
        _remoteStatus.value = _remoteStatus.value?.copy(lastSyncTimestampMs = timestampMs)
    }

    private fun launchCommand(
        type: CommandType,
        payloadJson: String,
        onSuccess: (AcknowledgementPayload) -> Unit = {}
    ) {
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val ack = sendCommandWithBoundedRetries(type, payloadJson.toByteArray(Charsets.UTF_8))
            val rtt = SystemClock.elapsedRealtime() - started
            if (ack == null) {
                val feedback = CommandFeedback(
                    commandType = type,
                    success = false,
                    errorCode = ProtocolErrorCode.INTERNAL_ERROR,
                    message = "Command timed out or BLE disconnected",
                    rttMs = rtt
                )
                recordAudit(UUID.randomUUID().toString(), feedback)
                _commandFeedback.emit(feedback)
                return@launch
            }
            if (ack.success) onSuccess(ack)
            val feedback = CommandFeedback(
                commandType = type,
                success = ack.success,
                errorCode = ack.errorCode,
                message = ack.message.ifBlank { if (ack.success) "$type OK" else "${ack.errorCode}" },
                rttMs = rtt
            )
            recordAudit(ack.commandId, feedback)
            _commandFeedback.emit(feedback)
        }
    }

    private suspend fun sendCommandWithBoundedRetries(
        type: CommandType,
        payloadBytes: ByteArray,
        allowDuringSync: Boolean = false
    ): AcknowledgementPayload? {
        val state = _bleState.value
        val validState = state == BleConnectionState.READY ||
            (allowDuringSync && state == BleConnectionState.SYNCHRONIZING)
        if (!validState) {
            return AcknowledgementPayload(
                commandId = UUID.randomUUID().toString(),
                commandType = type,
                success = false,
                errorCode = ProtocolErrorCode.AUTH_FAILED,
                message = "Not authenticated ($state)",
                serverTimeMs = System.currentTimeMillis()
            )
        }
        val cmdId = UUID.randomUUID().toString()
        val seq = sequenceGenerator.getAndIncrement()
        repeat(2) { attempt ->
            val ack = sendEnvelopeAndAwaitAck(
                commandType = type,
                payloadBytes = payloadBytes,
                authenticated = true,
                explicitCommandId = cmdId,
                explicitSequence = seq
            )
            if (ack != null) return ack
            val retryState = _bleState.value
            if (attempt == 0 && (retryState == BleConnectionState.READY || (allowDuringSync && retryState == BleConnectionState.SYNCHRONIZING))) {
                delay(250L)
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectInternal(address: String, autoConnect: Boolean): Boolean {
        if (!permissionManager.hasBlePermissions()) return false
        val btAdapter = adapter ?: return false
        if (!btAdapter.isEnabled) {
            _lastError.value = "Bluetooth adapter is disabled."
            return false
        }

        disconnect(userInitiated = false)
        targetAddress = address
        _bleState.value = BleConnectionState.CONNECTING

        val device = runCatching { btAdapter.getRemoteDevice(address) }.getOrNull() ?: run {
            _bleState.value = BleConnectionState.DISCONNECTED
            return false
        }

        val servicesReady = CompletableDeferred<Boolean>()
        servicesDeferred = servicesReady

        val gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: run {
                _bleState.value = BleConnectionState.DISCONNECTED
                return false
            }
        activeGatt = gatt

        val discovered = withTimeoutOrNull(15_000L) { servicesReady.await() } ?: false
        if (!discovered) {
            disconnect(userInitiated = false)
            return false
        }

        negotiateMtu(512)
        gattOpMutex.withLock {
            runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        }
        enableNotification(charStatus)
        enableNotification(charAck)
        enableNotification(charSync)
        readInitialConfigSnapshot()
        return true
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _bleState.value = BleConnectionState.CONNECTED
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sessionKeys = null
                sessionContext = ByteArray(0)
                servicesDeferred?.complete(false)
                _bleState.value = BleConnectionState.DISCONNECTED
                runCatching { gatt.close() }
                if (activeGatt == gatt) activeGatt = null

                if (!manualDisconnect) {
                    scheduleReconnectLoop()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesDeferred?.complete(false)
                return
            }
            val service = gatt.getService(BleProtocolConstants.SERVICE_UUID)
            if (service == null) {
                _lastError.value = "Target device does not expose AI Recorder GATT service."
                servicesDeferred?.complete(false)
                return
            }
            charCommand = service.getCharacteristic(BleProtocolConstants.CHAR_COMMAND_UUID)
            charStatus = service.getCharacteristic(BleProtocolConstants.CHAR_STATUS_UUID)
            charConfig = service.getCharacteristic(BleProtocolConstants.CHAR_CONFIG_UUID)
            charAck = service.getCharacteristic(BleProtocolConstants.CHAR_ACKNOWLEDGEMENT_UUID)
            charSync = service.getCharacteristic(BleProtocolConstants.CHAR_SYNC_UUID)
            charDeviceInfo = service.getCharacteristic(BleProtocolConstants.CHAR_DEVICE_INFO_UUID)
            servicesDeferred?.complete(charCommand != null && charStatus != null && charAck != null)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu.coerceIn(BleChunkFraming.MIN_ATT_MTU, 517)
            }
            mtuDeferred?.complete(negotiatedMtu)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            descriptorWriteDeferred?.complete(status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            characteristicWriteDeferred?.complete(status)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: ByteArray(0)
            characteristicReadDeferred?.complete(status to bytes)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            characteristicReadDeferred?.complete(status to value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleCharacteristicNotify(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicNotify(characteristic.uuid, value)
        }
    }

    private fun handleCharacteristicNotify(charUuid: UUID, frame: ByteArray) {
        android.util.Log.d("BleClientController", "Notify char=$charUuid bytes=${frame.size}")
        when (charUuid) {
            BleProtocolConstants.CHAR_STATUS_UUID -> {
                val full = statusReassembler.acceptFrame(frame) ?: return
                scope.launch { processStatusNotification(full) }
            }
            BleProtocolConstants.CHAR_ACKNOWLEDGEMENT_UUID -> {
                val full = ackReassembler.acceptFrame(frame) ?: return
                scope.launch { processAckNotification(full) }
            }
        }
    }

    private suspend fun processStatusNotification(rawBytes: ByteArray) {
        val env = ProtocolEnvelope.decode(rawBytes).getOrElse { err ->
            android.util.Log.w("BleClientController", "Failed decoding status envelope (${rawBytes.size}B): ${err.message}")
            return
        }
        when (StatusEventType.fromOpCode(env.opCode)) {
            StatusEventType.AUTH_CHALLENGE -> {
                val challenge = runCatching {
                    json.decodeFromString<AuthChallengePayload>(env.payload.toString(Charsets.UTF_8))
                }.getOrNull() ?: return

                if (_pendingSas.value != null) {
                    pendingAuthChallenge = challenge
                    return
                }
                respondToAuthChallenge(challenge)
            }
            StatusEventType.RECORDER_STATUS -> {
                val keys = sessionKeys ?: return
                if (!env.verifyHmac(keys.hmacKey, sessionContext)) return
                val status = runCatching {
                    json.decodeFromString<RecorderStatusPayload>(env.payload.toString(Charsets.UTF_8))
                }.getOrNull() ?: return
                _remoteStatus.value = status
                persistTelemetrySnapshot(status)
            }
            else -> Unit
        }
    }

    private suspend fun processAckNotification(rawBytes: ByteArray) {
        val env = ProtocolEnvelope.decode(rawBytes).getOrElse { err ->
            android.util.Log.w("BleClientController", "Failed decoding ACK envelope (${rawBytes.size}B): ${err.message}")
            return
        }
        val keys = sessionKeys
        if (keys != null && (env.flags and ProtocolEnvelope.FLAG_AUTHENTICATED) != 0) {
            if (!env.verifyHmac(keys.hmacKey, sessionContext)) {
                android.util.Log.w("BleClientController", "ACK HMAC verification failed for cmd=${env.commandId}")
                return
            }
        }
        val ack = runCatching {
            json.decodeFromString<AcknowledgementPayload>(env.payload.toString(Charsets.UTF_8))
        }.getOrElse { err ->
            android.util.Log.w("BleClientController", "Failed parsing ACK JSON: ${err.message}")
            return
        }
        android.util.Log.d("BleClientController", "ACK received cmd=${ack.commandType} ok=${ack.success} err=${ack.errorCode}")
        pendingAcks.remove(ack.commandId)?.complete(ack)
    }

    private suspend fun respondToAuthChallenge(challenge: AuthChallengePayload) {
        val masterKey = credentialsStore.getMasterKey(challenge.deviceId)
        if (masterKey == null) {
            _lastError.value = "Missing pairing credentials for ${challenge.deviceId}. Please re-pair."
            _bleState.value = BleConnectionState.DISCONNECTED
            return
        }
        _bleState.value = BleConnectionState.AUTHENTICATING

        val serverNonce = Base64.getDecoder().decode(challenge.serverNonceBase64)
        val clientNonce = ByteArray(16).also(secureRandom::nextBytes)
        val identity = credentialsStore.getOrCreateIdentity()
        val mac = ProtocolCrypto.computeChallengeResponse(
            masterKey = masterKey,
            serverNonce = serverNonce,
            clientNonce = clientNonce,
            clientDeviceId = identity.clientDeviceId
        )

        val verifyPayload = AuthVerifyPayload(
            clientDeviceId = identity.clientDeviceId,
            clientNonceBase64 = Base64.getEncoder().encodeToString(clientNonce),
            challengeResponseBase64 = Base64.getEncoder().encodeToString(mac),
            clientTimeMs = System.currentTimeMillis()
        )
        val ack = sendEnvelopeAndAwaitAck(
            commandType = CommandType.AUTH_VERIFY,
            payloadBytes = json.encodeToString(verifyPayload).toByteArray(Charsets.UTF_8),
            authenticated = false
        )
        if (ack != null && ack.success) {
            sessionKeys = ProtocolCrypto.deriveSessionKeys(masterKey, serverNonce, clientNonce)
            sessionContext = serverNonce + clientNonce
            sequenceGenerator.set(1L)
            _bleState.value = BleConnectionState.READY
            _lastError.value = null
            ack.dataJson?.let { raw ->
                runCatching { json.decodeFromString<RecorderStatusPayload>(raw) }.getOrNull()?.let { st ->
                    _remoteStatus.value = st
                    persistTelemetrySnapshot(st)
                }
            }
            refreshBattery()
            refreshStorage()
            refreshDeviceInfo()
        } else {
            _lastError.value = ack?.message ?: "Authentication handshake failed"
            _bleState.value = BleConnectionState.DISCONNECTED
        }
    }

    private suspend fun sendEnvelopeAndAwaitAck(
        commandType: CommandType,
        payloadBytes: ByteArray,
        authenticated: Boolean,
        explicitCommandId: String = UUID.randomUUID().toString(),
        explicitSequence: Long = 0L
    ): AcknowledgementPayload? = commandMutex.withLock {
        val gatt = activeGatt ?: return null
        val cmdChar = charCommand ?: return null

        val base = ProtocolEnvelope(
            flags = if (authenticated) {
                ProtocolEnvelope.FLAG_REQUIRES_ACK or ProtocolEnvelope.FLAG_AUTHENTICATED
            } else {
                ProtocolEnvelope.FLAG_REQUIRES_ACK
            },
            msgType = BleMessageType.COMMAND,
            opCode = commandType.opCode,
            commandId = explicitCommandId,
            sequenceNumber = explicitSequence,
            timestampMs = System.currentTimeMillis(),
            ttlMs = 30_000,
            payload = payloadBytes
        )
        val envelope = if (authenticated) {
            val keys = sessionKeys ?: return null
            base.signedWith(keys.hmacKey, sessionContext)
        } else {
            base
        }

        val ackDeferred = CompletableDeferred<AcknowledgementPayload>()
        pendingAcks[explicitCommandId] = ackDeferred

        val frames = BleChunkFraming.split(envelope.encode(), negotiatedMtu, msgIdGenerator.next())
        for (frame in frames) {
            val ok = writeCharacteristicRaw(gatt, cmdChar, frame)
            if (!ok) {
                pendingAcks.remove(explicitCommandId)
                return null
            }
        }

        val result = withTimeoutOrNull(8_000L) { ackDeferred.await() }
        pendingAcks.remove(explicitCommandId)
        return result
    }

    @SuppressLint("MissingPermission")
    private suspend fun negotiateMtu(requestedMtu: Int) = gattOpMutex.withLock {
        val gatt = activeGatt ?: return@withLock
        val deferred = CompletableDeferred<Int>()
        mtuDeferred = deferred
        if (gatt.requestMtu(requestedMtu)) {
            withTimeoutOrNull(3_000L) { deferred.await() }
        }
        mtuDeferred = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotification(characteristic: BluetoothGattCharacteristic?) = gattOpMutex.withLock {
        val gatt = activeGatt ?: return@withLock
        if (characteristic == null) return@withLock
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(BleProtocolConstants.CCCD_UUID) ?: return@withLock
        val deferred = CompletableDeferred<Int>()
        descriptorWriteDeferred = deferred
        val dispatched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
        if (dispatched) {
            withTimeoutOrNull(3_000L) { deferred.await() }
        }
        descriptorWriteDeferred = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeCharacteristicRaw(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray
    ): Boolean = gattOpMutex.withLock {
        val deferred = CompletableDeferred<Int>()
        characteristicWriteDeferred = deferred
        val dispatched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            }
        }
        val status = if (dispatched) withTimeoutOrNull(3_000L) { deferred.await() } else null
        characteristicWriteDeferred = null
        return status == BluetoothGatt.GATT_SUCCESS
    }

    @SuppressLint("MissingPermission")
    private suspend fun readCharacteristicRaw(characteristic: BluetoothGattCharacteristic?): ByteArray? =
        gattOpMutex.withLock {
            val gatt = activeGatt ?: return@withLock null
            if (characteristic == null) return@withLock null
            val deferred = CompletableDeferred<Pair<Int, ByteArray>>()
            characteristicReadDeferred = deferred
            val ok = gatt.readCharacteristic(characteristic)
            val res = if (ok) withTimeoutOrNull(3_000L) { deferred.await() } else null
            characteristicReadDeferred = null
            if (res?.first == BluetoothGatt.GATT_SUCCESS) res.second else null
        }

    private suspend fun readDeviceInfoSnapshot(): DeviceInfoPayload? {
        val raw = readCharacteristicRaw(charDeviceInfo) ?: return null
        return runCatching {
            json.decodeFromString<DeviceInfoPayload>(raw.toString(Charsets.UTF_8))
        }.getOrNull()?.also { _remoteDeviceInfo.value = it }
    }

    private suspend fun readInitialConfigSnapshot() {
        val raw = readCharacteristicRaw(charConfig) ?: return
        runCatching {
            json.decodeFromString<RecorderConfigPayload>(raw.toString(Charsets.UTF_8))
        }.getOrNull()?.let { _remoteConfig.value = it }
    }

    private suspend fun persistTelemetrySnapshot(status: RecorderStatusPayload) {
        val paired = pairedDao.getPairedDevice() ?: return
        pairedDao.upsert(
            paired.copy(
                lastConnectedAtMs = System.currentTimeMillis(),
                lastKnownRecorderState = status.recorderState,
                lastBatteryPct = status.batteryPct,
                lastFreeStorageBytes = status.freeStorageBytes,
                lastTotalStorageBytes = status.totalStorageBytes,
                lastPendingSyncCount = status.pendingSyncSegments,
                lastSyncTimestampMs = status.lastSyncTimestampMs ?: paired.lastSyncTimestampMs
            )
        )
    }

    private suspend fun recordAudit(commandId: String, feedback: CommandFeedback) {
        auditDao.insert(
            CommandAuditEntity(
                commandId = commandId,
                commandType = feedback.commandType,
                sentAtMs = System.currentTimeMillis(),
                success = feedback.success,
                errorCode = feedback.errorCode,
                message = feedback.message,
                rttMs = feedback.rttMs
            )
        )
    }

    private fun scheduleReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val delays = listOf(2_000L, 4_000L, 8_000L, 15_000L)
            for (waitMs in delays) {
                if (manualDisconnect || _bleState.value != BleConnectionState.DISCONNECTED) break
                delay(waitMs)
                val paired = pairedDao.getPairedDevice() ?: break
                if (connectInternal(paired.bleAddress, autoConnect = true)) {
                    break
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            if (Build.VERSION.SDK_INT >= 36) {
                addAction(BluetoothDevice.ACTION_KEY_MISSING)
            }
        }
        ContextCompat.registerReceiver(context, bondReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }
}
