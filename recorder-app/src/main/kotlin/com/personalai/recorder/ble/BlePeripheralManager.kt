package com.personalai.recorder.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.personalai.protocol.AcknowledgementPayload
import com.personalai.protocol.AuthChallengePayload
import com.personalai.protocol.AuthVerifyPayload
import com.personalai.protocol.BatteryInfoPayload
import com.personalai.protocol.BleChunkFraming
import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.BleMessageType
import com.personalai.protocol.BleProtocolConstants
import com.personalai.protocol.CommandReplayGuard
import com.personalai.protocol.CommandType
import com.personalai.protocol.CommandValidationResult
import com.personalai.protocol.DeviceInfoPayload
import com.personalai.protocol.FetchSegmentChunkRequestPayload
import com.personalai.protocol.FetchSegmentChunkResponsePayload
import com.personalai.protocol.MessageIdGenerator
import com.personalai.protocol.PacketReassembler
import com.personalai.protocol.PairingExchangeRequestPayload
import com.personalai.protocol.PingPayload
import com.personalai.protocol.PingResponsePayload
import com.personalai.protocol.ProtocolCrypto
import com.personalai.protocol.ProtocolEnvelope
import com.personalai.protocol.ProtocolErrorCode
import com.personalai.protocol.RecorderConfigPayload
import com.personalai.protocol.RecorderState
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
import com.personalai.protocol.SyncSegmentDescriptor
import com.personalai.protocol.SyncTransportMode
import com.personalai.recorder.data.db.DiagnosticSeverity
import com.personalai.recorder.data.prefs.RecorderPreferencesRepository
import com.personalai.recorder.data.repo.RecorderRepository
import com.personalai.recorder.domain.state.RecorderStateMachine
import com.personalai.recorder.health.DeviceHealthMonitor
import com.personalai.recorder.permissions.PermissionManager
import com.personalai.recorder.service.NotificationHelper
import com.personalai.recorder.service.RecorderService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BleHostRuntimeState(
    val isHosting: Boolean = false,
    val isAdvertising: Boolean = false,
    val connectedDeviceAddress: String? = null,
    val negotiatedMtu: Int = BleChunkFraming.MIN_ATT_MTU,
    val lastHostError: String? = null
)

@Singleton
class BlePeripheralManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateMachine: RecorderStateMachine,
    private val repository: RecorderRepository,
    private val prefs: RecorderPreferencesRepository,
    private val healthMonitor: DeviceHealthMonitor,
    private val permissionManager: PermissionManager,
    private val pairingStore: PairingCredentialsStore,
    private val dedupeStore: RoomCommandDedupeStore,
    private val notificationHelper: NotificationHelper,
    private val l2capSyncServer: L2capSyncServer
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()
    private val msgIdGenerator = MessageIdGenerator()
    private val reassembler = PacketReassembler()
    private val replayGuard = CommandReplayGuard(dedupeStore)
    private val notifyMutex = Mutex()
    private val hostStartElapsedMs = SystemClock.elapsedRealtime()

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _hostState = MutableStateFlow(BleHostRuntimeState())
    val hostState: StateFlow<BleHostRuntimeState> = _hostState.asStateFlow()

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var receiverRegistered = false

    private var connectedDevice: BluetoothDevice? = null
    private var negotiatedMtu: Int = BleChunkFraming.MIN_ATT_MTU
    private val cccdSubscriptions = mutableSetOf<UUID>()

    private var currentChallengeNonce: ByteArray? = null
    private var currentSessionKeys: SessionKeys? = null
    private var currentSessionContext: ByteArray = ByteArray(0)
    private val lastAuthChallengeElapsedMs = java.util.concurrent.atomic.AtomicLong(0L)

    private lateinit var charCommand: BluetoothGattCharacteristic
    private lateinit var charStatus: BluetoothGattCharacteristic
    private lateinit var charConfig: BluetoothGattCharacteristic
    private lateinit var charAck: BluetoothGattCharacteristic
    private lateinit var charSync: BluetoothGattCharacteristic
    private lateinit var charDeviceInfo: BluetoothGattCharacteristic

    private data class OutgoingNotify(
        val device: BluetoothDevice,
        val characteristic: BluetoothGattCharacteristic,
        val frame: ByteArray
    )

    private val notifyQueue = Channel<OutgoingNotify>(capacity = 256)
    private var notifyWorkerJob: Job? = null
    private var pendingNotifyAck: CompletableDeferred<Int>? = null
    private var statusPushJob: Job? = null

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (btState) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        resetConnectionSession()
                        stateMachine.updateBleState(BleConnectionState.DISCONNECTED)
                        _hostState.value = _hostState.value.copy(
                            isAdvertising = false,
                            connectedDeviceAddress = null,
                            lastHostError = "Bluetooth turned off; will resume automatically when re-enabled."
                        )
                    }
                    BluetoothAdapter.STATE_ON -> {
                        if (_hostState.value.isHosting) {
                            scope.launch {
                                runCatching { gattServer?.close() }
                                gattServer = null
                                val adapter = bluetoothAdapter ?: return@launch
                                val server = bluetoothManager.openGattServer(context, gattCallback)
                                if (server != null && server.addService(buildGattService())) {
                                    gattServer = server
                                    startAdvertising(adapter)
                                    _hostState.value = _hostState.value.copy(lastHostError = null)
                                    repository.logEvent("BLE_HOST", DiagnosticSeverity.INFO, "Auto-restarted BLE GATT server after Bluetooth turned ON")
                                }
                            }
                        }
                    }
                }
                return
            }

            // Strictly filter bond events to the connected/paired peer device so unrelated BT devices never log noise.
            val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val peerAddr = connectedDevice?.address ?: return
            if (dev != null && !dev.address.equals(peerAddr, ignoreCase = true)) return

            if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (bondState == BluetoothDevice.BOND_NONE) {
                    scope.launch {
                        repository.logEvent(
                            "BLE_BOND",
                            DiagnosticSeverity.INFO,
                            "OS bond state is BOND_NONE for peer $peerAddr (app-level ECDH session unaffected)"
                        )
                    }
                }
            } else if (Build.VERSION.SDK_INT >= 36 && action == BluetoothDevice.ACTION_KEY_MISSING) {
                scope.launch {
                    repository.logEvent(
                        "BLE_BOND",
                        DiagnosticSeverity.WARNING,
                        "Detected Android 16+ ACTION_KEY_MISSING for peer $peerAddr"
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startHosting(): Result<Unit> {
        if (_hostState.value.isHosting) return Result.success(Unit)

        val adapter = bluetoothAdapter
            ?: return Result.failure(IllegalStateException("Bluetooth hardware unavailable"))
        if (!adapter.isEnabled) {
            return Result.failure(IllegalStateException("Bluetooth adapter is disabled"))
        }
        if (!hasBleServerPermissions()) {
            return Result.failure(SecurityException("Missing BLUETOOTH_CONNECT or BLUETOOTH_ADVERTISE permission"))
        }

        registerBondReceiver()
        startNotifyWorker()

        val server = bluetoothManager.openGattServer(context, gattCallback)
            ?: return Result.failure(IllegalStateException("Unable to open BluetoothGattServer"))
        gattServer = server

        val service = buildGattService()
        if (!server.addService(service)) {
            server.close()
            gattServer = null
            return Result.failure(IllegalStateException("Failed adding GATT service"))
        }

        startAdvertising(adapter)
        startStatusObserver()

        _hostState.value = _hostState.value.copy(
            isHosting = true,
            lastHostError = null
        )
        scope.launch {
            repository.ensureDeviceInitialized()
            repository.logEvent("BLE_HOST", DiagnosticSeverity.INFO, "Started BLE GATT Server")
        }
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    fun stopHosting() {
        statusPushJob?.cancel()
        statusPushJob = null
        notifyWorkerJob?.cancel()
        notifyWorkerJob = null

        advertiseCallback?.let { cb ->
            runCatching { advertiser?.stopAdvertising(cb) }
        }
        advertiseCallback = null
        advertiser = null

        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null

        resetConnectionSession()
        unregisterBondReceiver()

        _hostState.value = BleHostRuntimeState(isHosting = false, isAdvertising = false)
        stateMachine.updateBleState(BleConnectionState.DISCONNECTED)
    }

    fun approvePendingPairingOnUi() {
        val addr = connectedDevice?.address
        val record = pairingStore.approvePendingPairing(addr) ?: return
        scope.launch {
            repository.setPairedPeer(record.pairedClientDeviceId, addr)
            repository.logEvent(
                "BLE_PAIRING",
                DiagnosticSeverity.INFO,
                "Approved pairing with Device A ${record.pairedClientDeviceId}"
            )
            // Immediately initiate authenticated session if still connected.
            connectedDevice?.let { dev ->
                sendAuthChallenge(dev)
            }
        }
    }

    fun rejectPendingPairingOnUi() {
        pairingStore.rejectPendingPairing()
        scope.launch {
            repository.logEvent("BLE_PAIRING", DiagnosticSeverity.WARNING, "User rejected pending pairing")
        }
    }

    fun unpairDevice() {
        pairingStore.unpair()
        resetConnectionSession()
        stateMachine.updateBleState(BleConnectionState.DISCONNECTED)
        scope.launch {
            repository.setPairedPeer(null, null)
            repository.logEvent("BLE_PAIRING", DiagnosticSeverity.INFO, "Cleared paired Device A credentials")
        }
    }

    private fun buildGattService(): BluetoothGattService {
        val service = BluetoothGattService(
            BleProtocolConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        charCommand = BluetoothGattCharacteristic(
            BleProtocolConstants.CHAR_COMMAND_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        charStatus = BluetoothGattCharacteristic(
            BleProtocolConstants.CHAR_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { addDescriptor(createCccdDescriptor()) }

        charConfig = BluetoothGattCharacteristic(
            BleProtocolConstants.CHAR_CONFIG_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        charAck = BluetoothGattCharacteristic(
            BleProtocolConstants.CHAR_ACKNOWLEDGEMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { addDescriptor(createCccdDescriptor()) }

        charSync = BluetoothGattCharacteristic(
            BleProtocolConstants.CHAR_SYNC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { addDescriptor(createCccdDescriptor()) }

        charDeviceInfo = BluetoothGattCharacteristic(
            BleProtocolConstants.CHAR_DEVICE_INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(charCommand)
        service.addCharacteristic(charStatus)
        service.addCharacteristic(charConfig)
        service.addCharacteristic(charAck)
        service.addCharacteristic(charSync)
        service.addCharacteristic(charDeviceInfo)
        return service
    }

    private fun createCccdDescriptor(): BluetoothGattDescriptor {
        return BluetoothGattDescriptor(
            BleProtocolConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter, includeDeviceNameOverride: Boolean? = null) {
        val bleAdvertiser = adapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            _hostState.value = _hostState.value.copy(
                isAdvertising = false,
                lastHostError = "BLE Peripheral Advertising unsupported on this device"
            )
            return
        }
        advertiser = bleAdvertiser

        advertiseCallback?.let { prev ->
            runCatching { bleAdvertiser.stopAdvertising(prev) }
        }
        advertiseCallback = null

        scope.launch {
            val device = repository.ensureDeviceInitialized()
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()

            // Primary adv packet: 3B flags + 18B 128-bit Service UUID = 21B (<= 31B legacy BLE limit).
            val advData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(BleProtocolConstants.SERVICE_UUID))
                .build()

            // Scan response: 12B Manufacturer Data (2B header + 2B ID + 8B deviceTag).
            // Only include OS BT name when UTF-8 length fits safely inside remaining 19B budget.
            val adapterNameBytes = runCatching { adapter.name?.toByteArray(Charsets.UTF_8)?.size ?: 99 }
                .getOrDefault(99)
            val includeName = includeDeviceNameOverride
                ?: (adapterNameBytes in 1..BleProtocolConstants.MAX_SAFE_ADV_NAME_UTF8_BYTES)

            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(includeName)
                .addManufacturerData(
                    BleProtocolConstants.BLE_MANUFACTURER_ID,
                    ProtocolCrypto.deviceTag8Bytes(device.deviceId)
                )
                .build()

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    _hostState.value = _hostState.value.copy(isAdvertising = true, lastHostError = null)
                }

                override fun onStartFailure(errorCode: Int) {
                    if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && includeName) {
                        // Retry immediately with strictly minimal 12-byte scan response (no device name).
                        startAdvertising(adapter, includeDeviceNameOverride = false)
                        return
                    }
                    val reason = when (errorCode) {
                        ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE (1)"
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS (2)"
                        ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED (3)"
                        ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR (4)"
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED (5)"
                        else -> "errorCode=$errorCode"
                    }
                    _hostState.value = _hostState.value.copy(
                        isAdvertising = false,
                        lastHostError = "Advertising failed ($reason)"
                    )
                    scope.launch {
                        repository.logEvent("BLE_ADV", DiagnosticSeverity.ERROR, "BLE advertising failed: $reason")
                    }
                }
            }
            advertiseCallback = callback
            bleAdvertiser.startAdvertising(settings, advData, scanResponse, callback)
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                negotiatedMtu = BleChunkFraming.MIN_ATT_MTU
                reassembler.clear()
                cccdSubscriptions.clear()
                replayGuard.resetSessionSequence()
                currentSessionKeys = null
                currentChallengeNonce = null
                lastAuthChallengeElapsedMs.set(0L)

                stateMachine.updateBleState(BleConnectionState.CONNECTED)
                _hostState.value = _hostState.value.copy(
                    connectedDeviceAddress = device.address,
                    negotiatedMtu = negotiatedMtu
                )
                scope.launch {
                    repository.logEvent("BLE_CONN", DiagnosticSeverity.INFO, "Device connected: ${device.address}")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device.address) {
                    resetConnectionSession()
                    // Critical invariant: Bluetooth disconnect NEVER alters RecorderState!
                    stateMachine.updateBleState(BleConnectionState.DISCONNECTED)
                    _hostState.value = _hostState.value.copy(
                        connectedDeviceAddress = null,
                        negotiatedMtu = BleChunkFraming.MIN_ATT_MTU
                    )
                    scope.launch {
                        repository.logEvent("BLE_CONN", DiagnosticSeverity.INFO, "Device disconnected (recorder unchanged)")
                    }
                    // Restart BLE advertising on chipsets that stop connectable ads after disconnect.
                    if (_hostState.value.isHosting) {
                        bluetoothAdapter?.takeIf { it.isEnabled }?.let { startAdvertising(it) }
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (connectedDevice?.address == device.address) {
                negotiatedMtu = mtu.coerceIn(BleChunkFraming.MIN_ATT_MTU, 517)
                _hostState.value = _hostState.value.copy(negotiatedMtu = negotiatedMtu)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            scope.launch {
                val payloadBytes: ByteArray = when (characteristic.uuid) {
                    BleProtocolConstants.CHAR_DEVICE_INFO_UUID -> {
                        val info = buildDeviceInfoPayload()
                        json.encodeToString(info).toByteArray(Charsets.UTF_8)
                    }
                    BleProtocolConstants.CHAR_STATUS_UUID -> {
                        val st = buildStatusPayload()
                        json.encodeToString(st).toByteArray(Charsets.UTF_8)
                    }
                    BleProtocolConstants.CHAR_CONFIG_UUID -> {
                        val cfg = prefs.settingsFlow.first().config
                        json.encodeToString(cfg).toByteArray(Charsets.UTF_8)
                    }
                    else -> ByteArray(0)
                }
                val slice = if (offset >= payloadBytes.size) {
                    ByteArray(0)
                } else {
                    payloadBytes.copyOfRange(offset, payloadBytes.size)
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == BleProtocolConstants.CCCD_UUID) {
                connectedDevice = device
                val charUuid = descriptor.characteristic.uuid
                val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                if (enabled) cccdSubscriptions.add(charUuid) else cccdSubscriptions.remove(charUuid)

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                }

                // Once client subscribes to STATUS & ACKNOWLEDGEMENT (including warm OS-bonded
                // ACL reconnects where STATE_DISCONNECTED never fired), issue a fresh auth challenge
                // debounced over a 1.5s window so consecutive CCCD writes don't double-send.
                if (enabled &&
                    (charUuid == BleProtocolConstants.CHAR_STATUS_UUID ||
                        charUuid == BleProtocolConstants.CHAR_ACKNOWLEDGEMENT_UUID) &&
                    cccdSubscriptions.contains(BleProtocolConstants.CHAR_STATUS_UUID) &&
                    cccdSubscriptions.contains(BleProtocolConstants.CHAR_ACKNOWLEDGEMENT_UUID) &&
                    pairingStore.getMasterKeyBytes() != null &&
                    !pairingStore.uiState.value.pairingWindowOpen
                ) {
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val prev = lastAuthChallengeElapsedMs.get()
                    if (nowElapsed - prev > 1_500L && lastAuthChallengeElapsedMs.compareAndSet(prev, nowElapsed)) {
                        currentSessionKeys = null
                        currentChallengeNonce = null
                        replayGuard.resetSessionSequence()
                        scope.launch { sendAuthChallenge(device) }
                    }
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            connectedDevice = device

            if (characteristic.uuid != BleProtocolConstants.CHAR_COMMAND_UUID &&
                characteristic.uuid != BleProtocolConstants.CHAR_CONFIG_UUID
            ) {
                return
            }

            val fullBytes = reassembler.acceptFrame(value) ?: return
            scope.launch {
                handleIncomingEnvelope(device, fullBytes)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pendingNotifyAck?.complete(status)
        }
    }

    private suspend fun sendAuthChallenge(device: BluetoothDevice) {
        val nonce = ByteArray(16).also(secureRandom::nextBytes)
        currentChallengeNonce = nonce
        stateMachine.updateBleState(BleConnectionState.AUTHENTICATING)

        val dev = repository.ensureDeviceInitialized()
        val challenge = AuthChallengePayload(
            serverNonceBase64 = Base64.getEncoder().encodeToString(nonce),
            serverTimeMs = System.currentTimeMillis(),
            deviceId = dev.deviceId,
            pairedDeviceId = pairingStore.getPairedClientId()
        )
        val env = ProtocolEnvelope(
            flags = 0,
            msgType = BleMessageType.STATUS_EVENT,
            opCode = StatusEventType.AUTH_CHALLENGE.opCode,
            commandId = UUID.randomUUID().toString(),
            sequenceNumber = 0L,
            timestampMs = System.currentTimeMillis(),
            ttlMs = 30_000,
            payload = json.encodeToString(challenge).toByteArray(Charsets.UTF_8)
        )
        enqueueEnvelope(device, charStatus, env)
    }

    private suspend fun handleIncomingEnvelope(device: BluetoothDevice, rawBytes: ByteArray) {
        val envelope = ProtocolEnvelope.decode(rawBytes).getOrElse { err ->
            repository.logEvent("BLE_PROTO", DiagnosticSeverity.WARNING, "Malformed BLE envelope: ${err.message}")
            return
        }

        val validation = replayGuard.validateIncoming(
            envelope = envelope,
            sessionHmacKey = currentSessionKeys?.hmacKey,
            sessionContext = currentSessionContext,
            allowPreAuthCommands = true
        )

        when (validation) {
            is CommandValidationResult.DuplicateCached -> {
                sendAck(device, validation.cachedAck)
            }
            is CommandValidationResult.Rejected -> {
                val cmdType = CommandType.fromOpCode(envelope.opCode) ?: CommandType.PING
                sendAck(
                    device,
                    AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = cmdType,
                        success = false,
                        errorCode = validation.errorCode,
                        message = validation.reason,
                        serverTimeMs = System.currentTimeMillis()
                    )
                )
            }
            is CommandValidationResult.Execute -> {
                val ack = executeCommand(device, envelope, validation.commandType)
                if (validation.commandType != CommandType.AUTH_VERIFY &&
                    validation.commandType != CommandType.PAIR_EXCHANGE &&
                    validation.commandType != CommandType.GET_DEVICE_INFO &&
                    validation.commandType != CommandType.FETCH_SEGMENT_CHUNK
                ) {
                    replayGuard.recordOutcome(envelope, ack)
                }
                sendAck(device, ack)
            }
        }
    }

    private suspend fun executeCommand(
        device: BluetoothDevice,
        envelope: ProtocolEnvelope,
        commandType: CommandType
    ): AcknowledgementPayload {
        val now = System.currentTimeMillis()
        val payloadText = envelope.payload.toString(Charsets.UTF_8)

        return when (commandType) {
            CommandType.GET_DEVICE_INFO -> {
                val info = buildDeviceInfoPayload()
                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    serverTimeMs = now,
                    dataJson = json.encodeToString(info)
                )
            }

            CommandType.PAIR_EXCHANGE -> {
                if (!pairingStore.uiState.value.pairingWindowOpen) {
                    return AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = false,
                        errorCode = ProtocolErrorCode.USER_INTERACTION_REQUIRED,
                        message = "Open Pairing screen on Device B and enable Pairing Mode first.",
                        serverTimeMs = now
                    )
                }
                val req = runCatching {
                    json.decodeFromString<PairingExchangeRequestPayload>(payloadText)
                }.getOrNull() ?: return invalidArgAck(envelope.commandId, commandType, now)

                val dev = repository.ensureDeviceInitialized()
                val resp = pairingStore.processIncomingPairExchange(dev.deviceId, dev.deviceName, req)
                repository.logEvent(
                    "BLE_PAIRING",
                    DiagnosticSeverity.INFO,
                    "Pairing challenge from ${req.clientDeviceName}; confirm SAS=${resp.sasCode} on Device B"
                )
                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    message = "Confirm 6-digit SAS ${resp.sasCode} on Device B",
                    serverTimeMs = now,
                    dataJson = json.encodeToString(resp)
                )
            }

            CommandType.AUTH_VERIFY -> {
                val req = runCatching {
                    json.decodeFromString<AuthVerifyPayload>(payloadText)
                }.getOrNull() ?: return invalidArgAck(envelope.commandId, commandType, now)

                val masterKey = pairingStore.getMasterKeyBytes()
                val expectedClientId = pairingStore.getPairedClientId()
                val serverNonce = currentChallengeNonce
                if (masterKey == null || expectedClientId == null || serverNonce == null) {
                    return AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = false,
                        errorCode = ProtocolErrorCode.AUTH_FAILED,
                        message = "Device not paired or challenge missing",
                        serverTimeMs = now
                    )
                }
                if (req.clientDeviceId != expectedClientId) {
                    return AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = false,
                        errorCode = ProtocolErrorCode.AUTH_FAILED,
                        message = "Client deviceId mismatch",
                        serverTimeMs = now
                    )
                }

                val clientNonce = Base64.getDecoder().decode(req.clientNonceBase64)
                val providedMac = Base64.getDecoder().decode(req.challengeResponseBase64)
                val expectedMac = ProtocolCrypto.computeChallengeResponse(
                    masterKey = masterKey,
                    serverNonce = serverNonce,
                    clientNonce = clientNonce,
                    clientDeviceId = req.clientDeviceId
                )

                if (!ProtocolCrypto.constantTimeEquals(expectedMac, providedMac)) {
                    stateMachine.updateBleState(BleConnectionState.DISCONNECTED)
                    return AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = false,
                        errorCode = ProtocolErrorCode.AUTH_FAILED,
                        message = "Challenge signature verification failed",
                        serverTimeMs = now
                    )
                }

                currentSessionKeys = ProtocolCrypto.deriveSessionKeys(masterKey, serverNonce, clientNonce)
                currentSessionContext = serverNonce + clientNonce
                replayGuard.resetSessionSequence()
                replayGuard.calibrateClockSkew(req.clientTimeMs, now)
                repository.markPeerConnected()
                stateMachine.updateBleState(BleConnectionState.READY)
                repository.logEvent("BLE_AUTH", DiagnosticSeverity.INFO, "Authenticated Device A session")

                // Push immediate status snapshot after auth succeeds.
                scope.launch { pushStatusSnapshot(device) }

                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    message = "Authenticated",
                    serverTimeMs = now,
                    dataJson = json.encodeToString(buildStatusPayload())
                )
            }

            CommandType.START_RECORDING -> {
                val _req = runCatching { json.decodeFromString<StartRecordingPayload>(payloadText) }.getOrNull()
                val recState = stateMachine.snapshot.value.recorderState
                if (!permissionManager.hasRecordAudioPermission()) {
                    return AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = false,
                        errorCode = ProtocolErrorCode.PERMISSION_MISSING,
                        message = "Microphone permission missing on Device B",
                        serverTimeMs = now
                    )
                }
                if (prefs.settingsFlow.first().privacyKillSwitch || recState == RecorderState.DISABLED) {
                    return AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = false,
                        errorCode = ProtocolErrorCode.USER_INTERACTION_REQUIRED,
                        message = "Privacy kill-switch is enabled on Device B",
                        serverTimeMs = now
                    )
                }
                when {
                    !RecorderService.isServiceRunning -> {
                        notificationHelper.postAlertNotification(
                            "Primary device requested recording",
                            "Open Sunbo (Secondary mode) and tap Arm & Start Recording (Android restricts background mic FGS launch)."
                        )
                        AcknowledgementPayload(
                            commandId = envelope.commandId,
                            commandType = commandType,
                            success = false,
                            errorCode = ProtocolErrorCode.SERVICE_NOT_RUNNING,
                            message = "SERVICE_NOT_RUNNING: Tap Arm Recorder on Device B first (Android 14+ blocks background mic FGS launch).",
                            serverTimeMs = now
                        )
                    }
                    recState == RecorderState.ARMED -> {
                        RecorderService.resumeFromStandby(context)
                        AcknowledgementPayload(
                            commandId = envelope.commandId,
                            commandType = commandType,
                            success = true,
                            message = "Resumed authorized recording from ARMED standby",
                            serverTimeMs = now
                        )
                    }
                    recState.isActivelyListening || recState == RecorderState.STARTING -> {
                        AcknowledgementPayload(
                            commandId = envelope.commandId,
                            commandType = commandType,
                            success = true,
                            message = "Recorder is already capturing ($recState)",
                            serverTimeMs = now
                        )
                    }
                    else -> {
                        AcknowledgementPayload(
                            commandId = envelope.commandId,
                            commandType = commandType,
                            success = false,
                            errorCode = ProtocolErrorCode.USER_INTERACTION_REQUIRED,
                            message = "Recorder in state $recState requires local user attention",
                            serverTimeMs = now
                        )
                    }
                }
            }

            CommandType.STOP_RECORDING -> {
                val req = runCatching { json.decodeFromString<StopRecordingPayload>(payloadText) }
                    .getOrDefault(StopRecordingPayload(StopMode.STOP_SERVICE))
                if (!RecorderService.isServiceRunning) {
                    return AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = true,
                        message = "Recorder already stopped",
                        serverTimeMs = now
                    )
                }
                if (req.mode == StopMode.STANDBY_PAUSE) {
                    RecorderService.pauseToStandby(context)
                    AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = true,
                        message = "Paused microphone capture to ARMED standby",
                        serverTimeMs = now
                    )
                } else {
                    RecorderService.stopEmergency(context)
                    AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = true,
                        message = "Stopped recording service cleanly",
                        serverTimeMs = now
                    )
                }
            }

            CommandType.GET_STATUS -> {
                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    serverTimeMs = now,
                    dataJson = json.encodeToString(buildStatusPayload())
                )
            }

            CommandType.GET_BATTERY -> {
                val health = healthMonitor.captureHealth()
                val payload = BatteryInfoPayload(
                    batteryPct = health.batteryPct,
                    isCharging = health.isCharging,
                    isPowerSaveMode = health.isPowerSaveMode,
                    thermalStatus = health.thermalStatus,
                    timestampMs = now
                )
                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    serverTimeMs = now,
                    dataJson = json.encodeToString(payload)
                )
            }

            CommandType.GET_STORAGE -> {
                val health = healthMonitor.captureHealth()
                val (usedBytes, pendingCount, totalCount) = repository.getStorageMetrics()
                val payload = StorageInfoPayload(
                    totalBytes = health.totalStorageBytes,
                    freeBytes = health.availableStorageBytes,
                    usedBySegmentsBytes = usedBytes,
                    pendingSyncSegments = pendingCount,
                    totalSegments = totalCount
                )
                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    serverTimeMs = now,
                    dataJson = json.encodeToString(payload)
                )
            }

            CommandType.SET_CONFIG -> {
                val cfg = runCatching { json.decodeFromString<RecorderConfigPayload>(payloadText) }.getOrNull()
                    ?: return invalidArgAck(envelope.commandId, commandType, now)
                if (cfg.minSpeechDurationMs >= cfg.maxSegmentDurationMs || cfg.vadThreshold !in 0.10f..0.95f) {
                    return invalidArgAck(envelope.commandId, commandType, now)
                }
                prefs.updateConfig(cfg)
                repository.logEvent(
                    "BLE_CONFIG",
                    DiagnosticSeverity.INFO,
                    "Updated remote config: localOnlyPauseSync=${cfg.localOnlyPauseSync}, thr=${cfg.vadThreshold}"
                )
                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    message = if (cfg.localOnlyPauseSync) {
                        "Local capture only enabled (sync/transcripts paused)"
                    } else {
                        "Configuration updated"
                    },
                    serverTimeMs = now,
                    dataJson = json.encodeToString(cfg)
                )
            }

            CommandType.SYNC_DATA -> {
                val req = runCatching { json.decodeFromString<SyncDataRequestPayload>(payloadText) }
                    .getOrDefault(SyncDataRequestPayload())
                val settings = prefs.settingsFlow.first()
                if (settings.config.localOnlyPauseSync) {
                    return AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = false,
                        errorCode = ProtocolErrorCode.BUSY,
                        message = "Sync & transcript processing paused (Local Capture Only mode active)",
                        serverTimeMs = now
                    )
                }
                val batch = repository.getPendingSyncBatch(req.cursorSequence, req.maxBatch)
                val (usedBytes, pendingCount, _) = repository.getStorageMetrics()
                val highestSeq = repository.getMaxSequenceNumber()
                val descriptors = batch.map { seg ->
                    SyncSegmentDescriptor(
                        segmentId = seg.segmentId,
                        sessionId = seg.sessionId,
                        sequenceNumber = seg.sequenceNumber,
                        startTimestampMs = seg.startTimestamp,
                        endTimestampMs = seg.endTimestamp,
                        durationMs = seg.duration,
                        wavSizeBytes = seg.fileSize,
                        sha256Checksum = seg.checksum
                    )
                }

                val encKey = currentSessionKeys?.encryptionKey
                val psm = if (req.preferL2cap && descriptors.isNotEmpty() && encKey != null) {
                    bluetoothAdapter?.let { adapter ->
                        l2capSyncServer.openChannelForSession(adapter, encKey)
                    }
                } else {
                    null
                }
                val transport = if (psm != null) SyncTransportMode.L2CAP_COC else SyncTransportMode.GATT_CHUNKED
                val summary = SyncManifestSummaryPayload(
                    pendingCount = pendingCount,
                    highestSequence = highestSeq,
                    totalPendingBytes = usedBytes,
                    l2capPsm = psm,
                    transportMode = transport,
                    segments = descriptors,
                    message = if (descriptors.isEmpty()) {
                        "All segments synchronized"
                    } else {
                        "Ready to transfer ${descriptors.size}/$pendingCount segment(s) via $transport"
                    }
                )
                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    serverTimeMs = now,
                    dataJson = json.encodeToString(summary)
                )
            }

            CommandType.FETCH_SEGMENT_CHUNK -> {
                val req = runCatching { json.decodeFromString<FetchSegmentChunkRequestPayload>(payloadText) }
                    .getOrNull() ?: return invalidArgAck(envelope.commandId, commandType, now)
                if (stateMachine.bleState.value == BleConnectionState.READY) {
                    stateMachine.updateBleState(BleConnectionState.SYNCHRONIZING)
                }
                val chunkRes = l2capSyncServer.readChunkForGattFallback(
                    segmentId = req.segmentId,
                    byteOffset = req.byteOffset,
                    maxBytes = req.maxBytes
                )
                if (chunkRes.isFailure) {
                    return AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = false,
                        errorCode = ProtocolErrorCode.INTERNAL_ERROR,
                        message = chunkRes.exceptionOrNull()?.message ?: "Failed reading segment chunk",
                        serverTimeMs = now
                    )
                }
                val (slice, totalBytes, eof) = chunkRes.getOrThrow()
                val resp = FetchSegmentChunkResponsePayload(
                    segmentId = req.segmentId,
                    byteOffset = req.byteOffset,
                    totalBytes = totalBytes,
                    eof = eof,
                    dataBase64 = Base64.getEncoder().encodeToString(slice)
                )
                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    serverTimeMs = now,
                    dataJson = json.encodeToString(resp)
                )
            }

            CommandType.SYNC_ACK -> {
                val req = runCatching { json.decodeFromString<SyncAckPayload>(payloadText) }
                    .getOrNull() ?: return invalidArgAck(envelope.commandId, commandType, now)
                l2capSyncServer.evictCachedSegment(req.segmentId)
                val ackRes = repository.acknowledgeSyncedSegment(req.segmentId, req.verifiedSha256)
                if (ackRes.isFailure) {
                    return AcknowledgementPayload(
                        commandId = envelope.commandId,
                        commandType = commandType,
                        success = false,
                        errorCode = ProtocolErrorCode.INVALID_ARGUMENT,
                        message = ackRes.exceptionOrNull()?.message ?: "ACK verification failed",
                        serverTimeMs = now
                    )
                }
                if (stateMachine.bleState.value == BleConnectionState.SYNCHRONIZING) {
                    stateMachine.updateBleState(BleConnectionState.READY)
                }
                scope.launch { pushStatusSnapshot(device) }
                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    message = "Segment #${req.sequenceNumber} acknowledged",
                    serverTimeMs = now
                )
            }

            CommandType.PING -> {
                val ping = runCatching { json.decodeFromString<PingPayload>(payloadText) }
                    .getOrDefault(PingPayload(now))
                // Re-calibrate clock skew on authenticated PING so overnight NTP drift never causes EXPIRED rejections.
                replayGuard.calibrateClockSkew(ping.clientTimeMs, now)
                val resp = PingResponsePayload(
                    clientTimeMs = ping.clientTimeMs,
                    serverTimeMs = now,
                    recorderState = stateMachine.snapshot.value.recorderState,
                    uptimeMs = SystemClock.elapsedRealtime() - hostStartElapsedMs
                )
                AcknowledgementPayload(
                    commandId = envelope.commandId,
                    commandType = commandType,
                    success = true,
                    serverTimeMs = now,
                    dataJson = json.encodeToString(resp)
                )
            }
        }
    }

    private fun invalidArgAck(commandId: String, type: CommandType, now: Long): AcknowledgementPayload {
        return AcknowledgementPayload(
            commandId = commandId,
            commandType = type,
            success = false,
            errorCode = ProtocolErrorCode.INVALID_ARGUMENT,
            message = "Invalid payload arguments",
            serverTimeMs = now
        )
    }

    private suspend fun buildStatusPayload(): RecorderStatusPayload {
        val snap = stateMachine.snapshot.value
        val health = healthMonitor.captureHealth()
        val (_, pendingCount, _) = repository.getStorageMetrics()
        val settings = prefs.settingsFlow.first()
        return RecorderStatusPayload(
            recorderState = snap.recorderState,
            activeSessionId = snap.activeSessionId,
            batteryPct = health.batteryPct,
            isCharging = health.isCharging,
            thermalStatus = health.thermalStatus,
            freeStorageBytes = health.availableStorageBytes,
            totalStorageBytes = health.totalStorageBytes,
            pendingSyncSegments = pendingCount,
            currentSessionSegments = snap.sessionSegmentsCount,
            interruptionReason = snap.interruptionReason ?: snap.lastError,
            localOnlyPauseSync = settings.config.localOnlyPauseSync,
            timestampMs = System.currentTimeMillis()
        )
    }

    private suspend fun buildDeviceInfoPayload(): DeviceInfoPayload {
        val dev = repository.ensureDeviceInitialized()
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
        return DeviceInfoPayload(
            deviceId = dev.deviceId,
            deviceName = dev.deviceName,
            firmwareVersion = appVersion,
            protocolVersion = BleProtocolConstants.PROTOCOL_VERSION,
            pairedDeviceId = pairingStore.getPairedClientId(),
            l2capSupported = true,
            maxMtuHint = 517,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT
        )
    }

    private suspend fun sendAck(device: BluetoothDevice, ack: AcknowledgementPayload) {
        val raw = json.encodeToString(ack).toByteArray(Charsets.UTF_8)
        val base = ProtocolEnvelope(
            flags = if (currentSessionKeys != null) ProtocolEnvelope.FLAG_AUTHENTICATED else 0,
            msgType = BleMessageType.ACK,
            opCode = ack.commandType.opCode,
            commandId = ack.commandId,
            sequenceNumber = 0L,
            timestampMs = ack.serverTimeMs,
            ttlMs = 30_000,
            payload = raw
        )
        val signed = currentSessionKeys?.let { base.signedWith(it.hmacKey, currentSessionContext) } ?: base
        enqueueEnvelope(device, charAck, signed)
    }

    private suspend fun pushStatusSnapshot(device: BluetoothDevice) {
        if (stateMachine.bleState.value != BleConnectionState.READY) return
        val statusPayload = buildStatusPayload()
        val base = ProtocolEnvelope(
            flags = ProtocolEnvelope.FLAG_AUTHENTICATED,
            msgType = BleMessageType.STATUS_EVENT,
            opCode = StatusEventType.RECORDER_STATUS.opCode,
            commandId = UUID.randomUUID().toString(),
            sequenceNumber = 0L,
            timestampMs = statusPayload.timestampMs,
            ttlMs = 30_000,
            payload = json.encodeToString(statusPayload).toByteArray(Charsets.UTF_8)
        )
        val signed = currentSessionKeys?.let { base.signedWith(it.hmacKey, currentSessionContext) } ?: base
        enqueueEnvelope(device, charStatus, signed)
    }

    private suspend fun enqueueEnvelope(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        envelope: ProtocolEnvelope
    ) {
        if (!cccdSubscriptions.contains(characteristic.uuid)) return
        val bytes = envelope.encode()
        val frames = BleChunkFraming.split(bytes, negotiatedMtu, msgIdGenerator.next())
        for (frame in frames) {
            notifyQueue.send(OutgoingNotify(device, characteristic, frame))
        }
    }

    private fun startNotifyWorker() {
        notifyWorkerJob?.cancel()
        notifyWorkerJob = scope.launch {
            for (item in notifyQueue) {
                if (connectedDevice?.address != item.device.address) continue
                sendSingleFrameAndWait(item.device, item.characteristic, item.frame)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendSingleFrameAndWait(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray
    ) = notifyMutex.withLock {
        val server = gattServer ?: return@withLock
        if (!hasBleServerPermissions()) return@withLock
        val deferred = CompletableDeferred<Int>()
        pendingNotifyAck = deferred

        val dispatched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val code = server.notifyCharacteristicChanged(device, characteristic, false, frame)
            if (code != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                android.util.Log.w("BlePeripheralManager", "notifyCharacteristicChanged failed code=$code len=${frame.size}")
            }
            code == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = frame
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }

        if (dispatched) {
            withTimeoutOrNull(2_000L) { deferred.await() }
        }
        pendingNotifyAck = null
    }

    @Suppress("OPT_IN_USAGE")
    private fun startStatusObserver() {
        statusPushJob?.cancel()
        statusPushJob = scope.launch {
            // CRITICAL: distinctUntilChanged() MUST precede debounce(250L); otherwise high-frequency
            // stateMachine emissions would continuously reset the debounce window and starve BLE pushes!
            combine(
                stateMachine.snapshot,
                repository.observePendingSyncCount(),
                prefs.settingsFlow
            ) { snap, pending, settings ->
                Triple(snap.recorderState, snap.sessionSegmentsCount to pending, settings.config.localOnlyPauseSync)
            }
                .distinctUntilChanged()
                .debounce(250L)
                .collect {
                    val dev = connectedDevice ?: return@collect
                    if (stateMachine.bleState.value == BleConnectionState.READY) {
                        pushStatusSnapshot(dev)
                    }
                }
        }
    }

    private fun resetConnectionSession() {
        l2capSyncServer.closeChannel()
        while (notifyQueue.tryReceive().isSuccess) {
            // Drain stale frames from previous connection so fast reconnects never send old packets.
        }
        connectedDevice = null
        negotiatedMtu = BleChunkFraming.MIN_ATT_MTU
        cccdSubscriptions.clear()
        reassembler.clear()
        replayGuard.resetSessionSequence()
        currentChallengeNonce = null
        currentSessionKeys = null
        currentSessionContext = ByteArray(0)
        lastAuthChallengeElapsedMs.set(0L)
        pendingNotifyAck?.cancel()
        pendingNotifyAck = null
        scope.launch {
            runCatching { repository.resetInterruptedSyncItems() }
        }
    }

    private fun hasBleServerPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun registerBondReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 36) {
                addAction(BluetoothDevice.ACTION_KEY_MISSING)
            }
        }
        ContextCompat.registerReceiver(context, bondReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(bondReceiver) }
        receiverRegistered = false
    }
}
