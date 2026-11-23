package com.personalai.primary.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.personalai.primary.permissions.PrimaryPermissionManager
import com.personalai.protocol.BleProtocolConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredRecorderDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val deviceTagHex: String?,
    val bondState: Int
)

@Singleton
class BleScannerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: PrimaryPermissionManager
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredRecorderDevice>>(emptyList())
    val discovered: StateFlow<List<DiscoveredRecorderDevice>> = _discovered.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_isScanning.value) return
        if (!permissionManager.hasBlePermissions()) {
            _scanError.value = "Bluetooth scan/connect permission required."
            return
        }
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _scanError.value = "Bluetooth is turned off."
            return
        }
        val leScanner = btAdapter.bluetoothLeScanner
        if (leScanner == null) {
            _scanError.value = "BLE scanner unavailable."
            return
        }

        _discovered.value = emptyList()
        _scanError.value = null
        scanner = leScanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleProtocolConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val scanRecord = result.scanRecord
                val tagBytes = scanRecord?.getManufacturerSpecificData(BleProtocolConstants.BLE_MANUFACTURER_ID)
                    ?: scanRecord?.getServiceData(ParcelUuid(BleProtocolConstants.SERVICE_UUID))
                val tagHex = tagBytes?.joinToString("") { "%02x".format(it) }
                val entry = DiscoveredRecorderDevice(
                    address = dev.address,
                    name = scanRecord?.deviceName ?: dev.name ?: "AI Memory Recorder",
                    rssi = result.rssi,
                    deviceTagHex = tagHex,
                    bondState = dev.bondState
                )
                _discovered.update { current ->
                    val others = current.filterNot { it.address == entry.address }
                    (others + entry).sortedByDescending { it.rssi }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _isScanning.value = false
                _scanError.value = "BLE scan failed (errorCode=$errorCode)"
            }
        }
        activeCallback = callback
        _isScanning.value = true
        leScanner.startScan(listOf(filter), settings, callback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) return
        activeCallback?.let { cb ->
            runCatching { scanner?.stopScan(cb) }
        }
        activeCallback = null
        scanner = null
        _isScanning.value = false
    }
}
